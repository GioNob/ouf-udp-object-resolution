package it.comune.trieste.ouf.udp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/udp/v1/governance/properties/conflicts")
public class PropertyGovernanceApi {
  private final PropertyGovernanceService service;private final HumanReviewBrowserGuard browser;
  public PropertyGovernanceApi(PropertyGovernanceService service,HumanReviewBrowserGuard browser){this.service=service;this.browser=browser;}
  @ModelAttribute void noStore(jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");response.setHeader("Vary","Authorization, Cookie");}
  @GetMapping List<Map<String,Object>> open(HttpServletRequest request){return service.open(GeometryGovernanceApi.initialProfile(request));}
  @GetMapping("/{id}") Map<String,Object> review(@PathVariable UUID id,HttpServletRequest request){var result=new LinkedHashMap<>(service.review(id,GeometryGovernanceApi.initialProfile(request)));String token=browser.token(request);if(token!=null)result.put("csrfToken",token);return result;}
  @PostMapping("/{id}/decisions") Map<String,UUID> decide(@PathVariable UUID id,@RequestBody Decision body,HttpServletRequest request){browser.require(request);return Map.of("decisionId",service.decide(id,body.expectedCurrentRevision(),body.chosenContribution(),body.reason(),TrustedHumanApi.trusted(request),GeometryGovernanceApi.initialProfile(request)));}
  public record Decision(UUID expectedCurrentRevision,UUID chosenContribution,String reason){}
}
