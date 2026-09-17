package it.comune.trieste.ouf.udp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/udp/v1/governance/geometry/issues")
public class GeometryGovernanceApi {
  private final GeometryGovernanceService service;
  public GeometryGovernanceApi(GeometryGovernanceService service){this.service=service;}
  @GetMapping("/{id}") Map<String,Object> review(@PathVariable UUID id,HttpServletRequest request){return service.review(id,initialProfile(request));}
  @PostMapping("/{id}/decisions") Map<String,UUID> decide(@PathVariable UUID id,@RequestBody Decision body,HttpServletRequest request){return Map.of("decisionId",service.decide(id,body.expectedCurrentRevision(),body.chosenRevision(),body.reason(),TrustedHumanApi.trusted(request),initialProfile(request)));}
  static ServingAuthorizationContext initialProfile(HttpServletRequest request){
    var verified=ServingApi.context(request);
    return new ServingAuthorizationContext(verified.principalType(),verified.subject(),verified.tenantId(),verified.capabilities(),Set.of("OPEN","ANONYMOUS"),verified.authorizationDecisionRef(),verified.correlationId(),verified.owner());
  }
  public record Decision(UUID expectedCurrentRevision,UUID chosenRevision,String reason){}
}
