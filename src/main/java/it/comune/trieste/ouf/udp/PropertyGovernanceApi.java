package it.comune.trieste.ouf.udp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/udp/v1/governance/properties/conflicts")
public class PropertyGovernanceApi {
  private final PropertyGovernanceService service;private final HumanReviewBrowserGuard browser;
  public PropertyGovernanceApi(PropertyGovernanceService service,HumanReviewBrowserGuard browser){this.service=service;this.browser=browser;}
  @GetMapping("/{id}") Map<String,Object> review(@PathVariable UUID id,HttpServletRequest request){var result=new LinkedHashMap<>(service.review(id,GeometryGovernanceApi.initialProfile(request)));result.put("csrfToken",browser.token(request));return result;}
  @PostMapping("/{id}/decisions") Map<String,UUID> decide(@PathVariable UUID id,@RequestBody Decision body,HttpServletRequest request){browser.require(request);return Map.of("decisionId",service.decide(id,body.expectedCurrentRevision(),body.chosenContribution(),body.reason(),TrustedHumanApi.trusted(request),GeometryGovernanceApi.initialProfile(request)));}
  public record Decision(UUID expectedCurrentRevision,UUID chosenContribution,String reason){}
}
