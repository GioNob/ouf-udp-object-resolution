package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;

import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.authorization.TrustedPrincipal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter;

class UdpIamSecurityConfigurationTest {
  private final UdpIamSecurityConfiguration configuration = new UdpIamSecurityConfiguration();

  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  private Jwt jwt(String actor, String scope) {
    return Jwt.withTokenValue("fixture-only")
        .header("alg", "RS256")
        .issuer("https://auth.example/realms/ouf")
        .subject("iam-subject")
        .audience(List.of("ouf-api-gateway"))
        .issuedAt(Instant.now().minusSeconds(10))
        .expiresAt(Instant.now().plusSeconds(300))
        .claim("tenant_id", "ouf-lab")
        .claim("ouf_actor_type", actor)
        .claim("acr", "1")
        .claim("scope", scope)
        .build();
  }

  @Test void verifiedHumanBearerBindsServerPrincipalForSdk() throws Exception {
    var authentication = configuration.udpTrustedJwtConverter("ouf-api-gateway")
        .convert(jwt("HUMAN", "urban.identity.preflight resolution.issue.read"));
    assertThat(authentication).isNotNull();
    SecurityContextHolder.getContext().setAuthentication(authentication);
    var request = new MockHttpServletRequest("POST", "/api/udp/v1/governance/identity/preflight");
    var response = new MockHttpServletResponse();
    var servletAdapter = new SecurityContextHolderAwareRequestFilter();
    servletAdapter.afterPropertiesSet();
    servletAdapter.doFilter(request, response, (adapted, res) ->
        new UdpIamSecurityConfiguration.TrustedPrincipalBridge().doFilter(
            adapted, res, (req, ignored) -> {
          var http = (jakarta.servlet.http.HttpServletRequest) req;
          assertThat(http.getUserPrincipal()).isInstanceOf(TrustedPrincipal.class);
          var principal = (TrustedPrincipal) http.getUserPrincipal();
          assertThat(principal.context().actorType()).isEqualTo(PrincipalContext.ActorType.HUMAN);
          assertThat(principal.context().tenantId()).isEqualTo("ouf-lab");
          assertThat(principal.context().scopes()).contains("urban.identity.preflight");
        }));
  }

  @Test void serviceRequiresCanonicalWorkloadAndInvalidActorFailsClosed() {
    var convert = configuration.udpTrustedJwtConverter("ouf-api-gateway");
    assertThatThrownBy(() -> convert.convert(jwt("SERVICE", "ouf.udp.identity.attestation.read")))
        .isInstanceOf(OAuth2AuthenticationException.class);
    assertThatThrownBy(() -> convert.convert(jwt("SERVICE_IDENTITY", "ouf.udp.identity.attestation.read")))
        .isInstanceOf(OAuth2AuthenticationException.class);
  }
}
