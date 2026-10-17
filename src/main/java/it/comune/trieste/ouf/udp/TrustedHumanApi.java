package it.comune.trieste.ouf.udp;

import jakarta.servlet.http.HttpServletRequest;
import it.comune.trieste.ouf.authorization.OwnerAuthorization;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.authorization.ResourceContext;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/udp/v1/governance")
public class TrustedHumanApi {
  private static final List<String> FORBIDDEN_HEADERS=List.of("X-Actor-Type","X-Actor-Subject","X-Tenant-Id","X-Capabilities","X-Authorization-Decision-Ref");
  private final IdentityGovernanceService service;
  private final ResolutionReviewPackageService packages;
  private final GovernedIdentityPreflight preflight;
  public TrustedHumanApi(IdentityGovernanceService service,ResolutionReviewPackageService packages,
      GovernedIdentityPreflight preflight){
    this.service=service;this.packages=packages;this.preflight=preflight;
  }

  @PostMapping("/merge/plans") IdentityGovernanceService.Plan planMerge(@RequestBody MergePlanRequest body,HttpServletRequest request){return service.planMerge(body.survivorObjectId(),body.mergedObjectId(),trusted(request));}
  @PostMapping("/merge/plans/{id}/execute") IdentityGovernanceService.Plan executeMerge(@PathVariable UUID id,@RequestBody ExecuteRequest body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request){return service.executeMerge(id,body.expectedVersion(),body.reason(),key,trusted(request));}
  @PostMapping("/split/plans") IdentityGovernanceService.Plan planSplit(@RequestBody SplitPlanRequest body,HttpServletRequest request){return service.planSplit(body.originalObjectId(),body.successorObjectIds(),body.bindingAllocations(),trusted(request));}
  @PostMapping("/split/plans/{id}/execute") IdentityGovernanceService.Plan executeSplit(@PathVariable UUID id,@RequestBody ExecuteRequest body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request){return service.executeSplit(id,body.expectedVersion(),body.reason(),key,trusted(request));}
  @PostMapping("/resolution/issues/{id}/decisions") void decideIssue(@PathVariable UUID id,@RequestBody IssueDecisionRequest body,HttpServletRequest request){service.decideResolutionIssue(id,body.expectedVersion(),body.action(),body.reason(),body.targetUrbanObjectId(),trusted(request));}
  @GetMapping("/resolution/issues/package") ResolutionReviewPackageService.PackageView reviewPackage(HttpServletRequest request){return packages.prepare(resolutionPackageActor(request,"resolution.issue.read"));}
  /** MCP reads use a separately authorized delegated HUMAN request and cannot commit decisions. */
  @PostMapping("/internal/resolution/issues/package") ResolutionReviewPackageService.PackageView delegatedReviewPackage(HttpServletRequest request){
    return packages.prepare(resolutionPackageActor(request,"resolution.issue.read"));
  }
  @PostMapping("/resolution/issues/package/confirm") ResolutionReviewPackageService.Confirmation confirmReviewPackage(@RequestBody PackageConfirmationRequest body,HttpServletRequest request){
    try{return packages.confirm(body.snapshotHash(),body.choices(),resolutionPackageActor(request,"resolution.match.approve"));}
    catch(IllegalArgumentException error){
      String code=error.getMessage()!=null&&Set.of("UDP_REVIEW_PACKAGE_INVALID","UDP_REASON_TOO_LONG","UDP_ISSUE_ACTION_INVALID",
          "UDP_TARGET_NOT_A_CANDIDATE","UDP_NEW_IDENTITY_DECISION_INVALID").contains(error.getMessage())
          ?error.getMessage():"UDP_REVIEW_REQUEST_INVALID";
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,code);
    }}
  @GetMapping("/objects/{id}/identity") Map<String,Object> identity(@PathVariable UUID id,HttpServletRequest request){TrustedHumanContext actor=trusted(request);actor.require("urban.object.read");return service.resolveIdentity(id);}
  @GetMapping("/plans/{id}") IdentityGovernanceService.Plan plan(@PathVariable UUID id,HttpServletRequest request){TrustedHumanContext actor=trusted(request);actor.require("resolution.issue.read");return service.getPlan(id);}
  @PostMapping("/identity/preflight") GovernedIdentityPreflight.Attestation prepareIdentity(
      @RequestBody IdentityPreflightRequest body,HttpServletRequest request){
    return preflight.prepare(body.sourceId(),body.configurationHash(),body.configuration(),trusted(request));
  }
  @GetMapping("/identity/preflight") GovernedIdentityPreflight.Attestation identityPreflight(
      @RequestParam UUID id,HttpServletRequest request){return preflight.status(id,trusted(request));}
  @GetMapping("/internal/identity/preflight") GovernedIdentityPreflight.Attestation identityPreflightForOnboarding(
      @RequestParam String configurationHash,@RequestParam String sourceId,HttpServletRequest request){
    var owner=OwnerAuthorization.bind(request);
    if(owner.principal().actorType()!=PrincipalContext.ActorType.SERVICE)
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"UDP_IDENTITY_PREFLIGHT_SERVICE_REQUIRED");
    String tenant=owner.principal().tenantId();
    owner.require("ouf.udp.identity.attestation.read",new ResourceContext("identity-preflight",sourceId,tenant,null,Map.of()));
    return preflight.latest(configurationHash,sourceId,tenant);
  }

  /**
   * The tenant-wide package contains mixed, not individually classified evidence.
   * Require full governed review coverage; never invent a lowest/default label,
   * infer an ordering between opaque labels, or accept classification from callers.
   * A future narrower review must classify/filter each issue before releasing it.
   */
  static TrustedHumanContext resolutionPackageActor(HttpServletRequest request,String capability){
    if(!Set.of("resolution.issue.read","resolution.match.approve").contains(capability))
      throw new IllegalArgumentException("UDP_REVIEW_CAPABILITY_INVALID");
    for(String h:FORBIDDEN_HEADERS)if(request.getHeader(h)!=null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UDP_UNTRUSTED_ACTOR_HEADER");
    var owner=OwnerAuthorization.bind(request);
    if(owner.principal().actorType()!=PrincipalContext.ActorType.HUMAN)
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"UDP_REVIEW_HUMAN_REQUIRED");
    String tenant=owner.principal().tenantId();
    // Full review is intentionally stricter than a single labelled serving read.
    for(String label:List.of("ANONYMOUS","OPEN","PERSONAL","RESTRICTED","SENSITIVE"))
      for(String detail:List.of("PUBLIC_OPERATIONAL","TENANT_OPERATIONAL",
          "RESTRICTED_OPERATIONAL","SECURITY_SENSITIVE"))
        owner.require(capability,new ResourceContext("resolution-issues",null,tenant,null,
            Map.of("module","UDP","requiresDataAccessLabel","true",
                "dataAccessLabel",label,"detailLevel",detail)));
    String correlation=request.getAttribute("ouf.correlationId") instanceof String value&&!value.isBlank()
        ?value:UUID.randomUUID().toString();
    return new TrustedHumanContext("HUMAN",owner.principal().subjectId(),tenant,
        Set.of(capability),owner.decisionRef(),correlation);
  }

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
  public record PackageConfirmationRequest(String snapshotHash,List<ResolutionReviewPackageService.Choice> choices){}
  public record IdentityPreflightRequest(String sourceId,String configurationHash,
      Map<String,Object> configuration){}
}

