package it.comune.trieste.ouf.udp;

import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.authorization.TrustedPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

/** Binds a validated IAM bearer to the SDK's server-only TrustedPrincipal SPI. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ouf.udp.iam.enabled", havingValue = "true")
public class UdpIamSecurityConfiguration {
  private static OAuth2AuthenticationException invalid(String reason) {
    return new OAuth2AuthenticationException(new OAuth2Error("invalid_token", reason, null));
  }

  @Bean JwtDecoder udpJwtDecoder(@Value("${ouf.udp.iam.issuer}") String issuer,
                                 @Value("${ouf.udp.iam.audience}") String audience) {
    if (issuer.isBlank() || audience.isBlank()) throw new IllegalStateException("UDP_IAM_BINDING_REQUIRED");
    var decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
    OAuth2TokenValidator<Jwt> aud = jwt -> jwt.getAudience().contains(audience)
        ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "UDP audience missing", null));
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), aud));
    return decoder;
  }

  @Bean Converter<Jwt, ? extends AbstractAuthenticationToken> udpTrustedJwtConverter(
      @Value("${ouf.udp.iam.audience}") String audience) {
    return jwt -> {
      String subject = text(jwt.getClaim("ouf_subject"));
      if (subject == null) subject = text(jwt.getSubject());
      String tenant = text(jwt.getClaim("tenant_id"));
      String actor = text(jwt.getClaim("ouf_actor_type"));
      String acr = text(jwt.getClaim("acr"));
      if (subject == null || tenant == null || actor == null || acr == null) throw invalid("UDP identity claims missing");
      PrincipalContext.ActorType type;
      try { type = PrincipalContext.ActorType.valueOf(actor); }
      catch (IllegalArgumentException error) { throw invalid("UDP actor type invalid"); }
      String service = null;
      if (type == PrincipalContext.ActorType.SERVICE || type == PrincipalContext.ActorType.AI_AGENT) {
        service = text(jwt.getClaim("client_id"));
        if (service == null) service = text(jwt.getClaim("azp"));
        if (service == null) throw invalid("UDP service principal missing");
      }
      Set<String> scopes = strings(jwt.getClaim("scope"));
      Set<String> roles = roles(jwt);
      var claims = new PrincipalContext.IdentityClaims(roles, acr, strings(jwt.getClaim("amr")),
          jwt.getClaim("auth_time") instanceof Number n ? Instant.ofEpochSecond(n.longValue()) : null);
      var context = new PrincipalContext(subject, tenant, type, service, acr,
          jwt.getIssuer().toString(), audience, scopes, claims);
      Set<GrantedAuthority> authorities = new LinkedHashSet<>();
      for (String scope : scopes) authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
      return new TrustedJwtToken(jwt, new TrustedPrincipal(context), authorities);
    };
  }

  @Bean SecurityFilterChain udpIamChain(HttpSecurity http,
      Converter<Jwt, ? extends AbstractAuthenticationToken> udpTrustedJwtConverter) throws Exception {
    http.csrf(csrf -> csrf.disable());
    http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    http.authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/udp/v1/governance/**", "/api/internal/v1/**").authenticated()
        .anyRequest().permitAll());
    http.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(udpTrustedJwtConverter)));
    http.addFilterAfter(new TrustedPrincipalBridge(), BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  private static String text(Object value) {
    if (value == null) return null;
    String result = String.valueOf(value).trim();
    return result.isEmpty() ? null : result;
  }

  private static Set<String> strings(Object raw) {
    if (raw instanceof String value) {
      if (value.isBlank()) return Set.of();
      return Set.of(value.trim().split("\\s+"));
    }
    if (raw instanceof Collection<?> values) {
      Set<String> result = new LinkedHashSet<>();
      for (Object value : values) if (text(value) != null) result.add(text(value));
      return Set.copyOf(result);
    }
    return Set.of();
  }

  private static Set<String> roles(Jwt jwt) {
    Object canonical = jwt.getClaim("externalRoleRefs"), legacy = jwt.getClaim("external_role_refs");
    Set<String> result = roleArray(canonical == null ? legacy : canonical);
    if (canonical != null && legacy != null && !result.equals(roleArray(legacy))) throw invalid("UDP role claims conflict");
    return result;
  }

  private static Set<String> roleArray(Object raw) {
    if (raw == null) return Set.of();
    if (!(raw instanceof Collection<?> values) || values.size() > 32) throw invalid("UDP role claims invalid");
    Set<String> result = new LinkedHashSet<>(); int length = 0;
    for (Object value : values) {
      if (!(value instanceof String role) || !role.matches("[A-Za-z0-9_:./-]{1,128}") || !result.add(role))
        throw invalid("UDP role claim invalid");
      length += role.length() + 1;
    }
    if (length > 4097) throw invalid("UDP role claims too long");
    return Set.copyOf(result);
  }

  static final class TrustedJwtToken extends AbstractAuthenticationToken {
    private final Jwt jwt;
    private final TrustedPrincipal principal;
    TrustedJwtToken(Jwt jwt, TrustedPrincipal principal, Collection<? extends GrantedAuthority> authorities) {
      super(authorities); this.jwt = jwt; this.principal = principal; setAuthenticated(true);
    }
    @Override public Object getCredentials() { return ""; }
    @Override public TrustedPrincipal getPrincipal() { return principal; }
    @Override public String getName() { return principal.getName(); }
  }

  static final class TrustedPrincipalBridge extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      var authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication != null && authentication.isAuthenticated()
          && authentication.getPrincipal() instanceof TrustedPrincipal principal) {
        chain.doFilter(new HttpServletRequestWrapper(request) {
          @Override public Principal getUserPrincipal() { return principal; }
        }, response);
      } else chain.doFilter(request, response);
    }
  }
}

/** Avoid Spring Boot's default generated login when the guarded IAM adapter is disabled. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ouf.udp.iam.enabled", havingValue = "false", matchIfMissing = true)
class UdpIamDisabledConfiguration {
  @Bean SecurityFilterChain udpIamDisabledSentinel(HttpSecurity http) throws Exception {
    http.securityMatcher("/__ouf/iam-disabled/**");
    http.csrf(csrf -> csrf.disable());
    http.authorizeHttpRequests(auth -> auth.anyRequest().denyAll());
    return http.build();
  }
}
