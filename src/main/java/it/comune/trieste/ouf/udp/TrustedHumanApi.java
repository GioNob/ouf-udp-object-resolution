package it.comune.trieste.ouf.udp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/udp/v1/governance")
public class TrustedHumanApi {
  private static final List<String> FORBIDDEN_HEADERS=List.of("X-Actor-Type","X-Actor-Subject","X-Tenant-Id","X-Capabilities","X-Authorization-Decision-Ref");
  private final IdentityGovernanceService service;public TrustedHumanApi(IdentityGovernanceService service){this.service=service;}

  @PostMapping("/merge/plans") IdentityGovernanceService.Plan planMerge(@RequestBody MergePlanRequest body,HttpServletRequest request){return service.planMerge(body.survivorObjectId(),body.mergedObjectId(),trusted(request));}
  @PostMapping("/merge/plans/{id}/execute") IdentityGovernanceService.Plan executeMerge(@PathVariable UUID id,@RequestBody ExecuteRequest body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request){return service.executeMerge(id,body.expectedVersion(),body.reason(),key,trusted(request));}
  @PostMapping("/split/plans") IdentityGovernanceService.Plan planSplit(@RequestBody SplitPlanRequest body,HttpServletRequest request){return service.planSplit(body.originalObjectId(),body.successorObjectIds(),body.bindingAllocations(),trusted(request));}
  @PostMapping("/split/plans/{id}/execute") IdentityGovernanceService.Plan executeSplit(@PathVariable UUID id,@RequestBody ExecuteRequest body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request){return service.executeSplit(id,body.expectedVersion(),body.reason(),key,trusted(request));}
  @PostMapping("/resolution/issues/{id}/decisions") void decideIssue(@PathVariable UUID id,@RequestBody IssueDecisionRequest body,HttpServletRequest request){service.decideResolutionIssue(id,body.expectedVersion(),body.action(),body.reason(),body.targetUrbanObjectId(),trusted(request));}
  @GetMapping("/objects/{id}/identity") Map<String,Object> identity(@PathVariable UUID id,HttpServletRequest request){TrustedHumanContext actor=trusted(request);actor.require("urban.object.read");return service.resolveIdentity(id);}
  @GetMapping("/plans/{id}") IdentityGovernanceService.Plan plan(@PathVariable UUID id,HttpServletRequest request){TrustedHumanContext actor=trusted(request);actor.require("resolution.issue.read");return service.getPlan(id);}

  static TrustedHumanContext trusted(HttpServletRequest request){
    for(String h:FORBIDDEN_HEADERS)if(request.getHeader(h)!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UDP_UNTRUSTED_ACTOR_HEADER");
    try{var c=it.comune.trieste.ouf.authorization.ServletAuthorization.resolve(request);
      String correlation=request.getAttribute("ouf.correlationId") instanceof String value&&!value.isBlank()?value:UUID.randomUUID().toString();
      return new TrustedHumanContext(c.principal().actorType().name(),c.principal().subjectId(),c.principal().tenantId(),c.capabilities(),c.decisionRef(),correlation);
    }catch(SecurityException e){throw new ResponseStatusException(HttpStatus.FORBIDDEN,e.getMessage());}
  }
  public record MergePlanRequest(UUID survivorObjectId,UUID mergedObjectId){}
  public record SplitPlanRequest(UUID originalObjectId,List<UUID> successorObjectIds,Map<String,String> bindingAllocations){}
  public record ExecuteRequest(long expectedVersion,String reason){}
  public record IssueDecisionRequest(long expectedVersion,String action,String reason,UUID targetUrbanObjectId){}
}
