package it.comune.trieste.ouf.udp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/udp/v1/governance/lake/shadow-rebuilds")
public class LakeShadowRebuildApi {
  private final LakeShadowRebuildService service;public LakeShadowRebuildApi(LakeShadowRebuildService service){this.service=service;}
  @PostMapping LakeShadowRebuildService.Plan plan(@RequestBody PlanRequest body,HttpServletRequest request){return service.plan(body.tier(),TrustedHumanApi.trusted(request));}
  @GetMapping("/{id}") LakeShadowRebuildService.Plan get(@PathVariable UUID id,HttpServletRequest request){var actor=TrustedHumanApi.trusted(request);actor.require("lake.shadow.read");return service.get(id,actor.tenantId());}
  @PostMapping("/{id}/cutover") LakeShadowRebuildService.Plan cutover(@PathVariable UUID id,@RequestBody CutoverRequest body,HttpServletRequest request){return service.cutover(id,body.expectedVersion(),body.reason(),TrustedHumanApi.trusted(request));}
  record PlanRequest(String tier){}record CutoverRequest(long expectedVersion,String reason){}
}
