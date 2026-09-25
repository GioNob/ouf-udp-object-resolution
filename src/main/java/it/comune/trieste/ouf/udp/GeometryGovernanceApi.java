package it.comune.trieste.ouf.udp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/udp/v1/governance/geometry/issues")
public class GeometryGovernanceApi {
  private final GeometryGovernanceService service;private final HumanReviewBrowserGuard browser;
  public GeometryGovernanceApi(GeometryGovernanceService service,HumanReviewBrowserGuard browser){this.service=service;this.browser=browser;}
  @ModelAttribute void noStore(jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");response.setHeader("Vary","Authorization, Cookie");}
  @GetMapping List<Map<String,Object>> open(HttpServletRequest request){return service.open(initialProfile(request));}
  @GetMapping("/{id}") Map<String,Object> review(@PathVariable UUID id,HttpServletRequest request){var result=new LinkedHashMap<>(service.review(id,initialProfile(request)));String token=browser.token(request);if(token!=null)result.put("csrfToken",token);return result;}
  @PostMapping("/{id}/decisions") Map<String,UUID> decide(@PathVariable UUID id,@RequestBody Decision body,HttpServletRequest request){browser.require(request);return Map.of("decisionId",service.decide(id,body.expectedCurrentRevision(),body.chosenRevision(),body.reason(),TrustedHumanApi.trusted(request),initialProfile(request)));}
  static ServingAuthorizationContext initialProfile(HttpServletRequest request){
    var verified=ServingApi.context(request);
    return new ServingAuthorizationContext(verified.principalType(),verified.subject(),verified.tenantId(),verified.capabilities(),Set.of("OPEN","ANONYMOUS"),verified.authorizationDecisionRef(),verified.correlationId(),verified.owner());
  }
  public record Decision(UUID expectedCurrentRevision,UUID chosenRevision,String reason){}
}
