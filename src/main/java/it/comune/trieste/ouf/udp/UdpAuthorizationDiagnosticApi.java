package it.comune.trieste.ouf.udp;

import it.comune.trieste.ouf.authorization.LocalAuthorization;
import it.comune.trieste.ouf.authorization.ServletAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Temporary, opt-in diagnostic used only on an isolated R4a clone. */
@RestController
@ConditionalOnProperty(name = "ouf.udp.authorization-diagnostic.enabled", havingValue = "true")
final class UdpAuthorizationDiagnosticApi {
  private static final String CAPABILITY = "urban.identity.preflight";
  private final LocalAuthorization engine;

  UdpAuthorizationDiagnosticApi(LocalAuthorization engine) { this.engine = engine; }

  @GetMapping("/api/internal/v1/udp/authorization/diagnostic")
  Map<String, Object> inspect(HttpServletRequest request) {
    var context = ServletAuthorization.resolve(request);
    var health = engine.health();
    var decision = context.decisions().get(CAPABILITY);
    var principal = context.principal();
    var snapshot = context.snapshot();
    var matchingGrants = snapshot.bundle().grants().stream()
        .filter(g -> CAPABILITY.equals(g.capabilityId())).toList();
    var subjectMatches = matchingGrants.stream()
        .filter(g -> g.subjectId() == null || g.subjectId().equals(principal.subjectId()))
        .count();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("bundleId", snapshot.bundle().bundleId());
    result.put("bundleVersion", snapshot.bundle().version());
    result.put("bundleReady", health.ready());
    result.put("lastRefreshError", health.lastError() == null ? "NONE" : health.lastError());
    result.put("actorType", principal.actorType().name());
    result.put("scopePresent", principal.scopes().contains(CAPABILITY));
    result.put("grantCount", matchingGrants.size());
    result.put("subjectMatchCount", subjectMatches);
    result.put("decisionCode", decision == null ? "NO_DESCRIPTOR" : decision.decisionCode());
    result.put("allowed", decision != null && decision.allowed());
    return result;
  }
}
