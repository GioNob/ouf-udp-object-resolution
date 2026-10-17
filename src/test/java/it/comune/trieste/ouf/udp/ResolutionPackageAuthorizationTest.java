package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/** Real SDK evaluation with the constrained v40 grant shape observed on target. */
class ResolutionPackageAuthorizationTest {
 private static final Set<String> CAPS=Set.of("resolution.issue.read","resolution.match.approve");
 private static final Set<String> LABELS=Set.of("ANONYMOUS","OPEN","PERSONAL","RESTRICTED","SENSITIVE");
 private static final Set<String> DETAILS=Set.of("PUBLIC_OPERATIONAL","TENANT_OPERATIONAL","RESTRICTED_OPERATIONAL","SECURITY_SENSITIVE");
 private static GrantConstraints constraints(String effect,Set<String> labels,Set<String> details){
  return new GrantConstraints(effect,null,null,null,Map.of(),labels,details,null,Set.of(),null);
 }
 private static MockHttpServletRequest request(String actor,String subject,Set<String> scopes,
     Set<String> labels,Set<String> details,boolean deny)throws Exception{
  var request=new MockHttpServletRequest();TestAuthorization.bind(request,subject,actor,scopes);
  var runtime=(LocalAuthorization)request.getServletContext().getAttribute(ServletAuthorization.RUNTIME);
  var now=Instant.now();var descriptors=CAPS.stream().sorted().map(cap->new CapabilityDescriptor(cap,
      cap.endsWith("read")?"READ":"COMMAND",cap,Set.of(PrincipalContext.ActorType.HUMAN))).toList();
  List<Grant> grants=new ArrayList<>();
  for(String cap:CAPS){
   grants.add(new Grant("allow:"+cap,cap,"tenant-a","alice",null,null,now.minusSeconds(60),now.plusSeconds(3600),constraints("ALLOW",labels,details)));
   if(deny)grants.add(new Grant("deny:"+cap,cap,"tenant-a","alice",null,null,now.minusSeconds(60),now.plusSeconds(3600),constraints("DENY",Set.of("SENSITIVE"),Set.of("SECURITY_SENSITIVE"))));
  }
  TestAuthorization.install(runtime,new PolicyBundle("fixture",40,now,descriptors,grants));return request;
 }
 private static MockHttpServletRequest valid()throws Exception{return request("HUMAN","alice",CAPS,LABELS,DETAILS,false);}
 @Test void reproducesEmptyAttributeDenialThenAuthorizesFullPackageWithSameGrants()throws Exception{
  var request=valid();
  assertThatThrownBy(()->TrustedHumanApi.trusted(request).require("resolution.issue.read"))
      .hasMessage("UDP_TRUSTED_HUMAN_REQUIRED");
  var actor=TrustedHumanApi.resolutionPackageActor(request,"resolution.issue.read");
  actor.require("resolution.issue.read");assertThat(actor.capabilities()).containsExactly("resolution.issue.read");
  assertThat(actor.subject()).isEqualTo("alice");assertThat(actor.tenantId()).isEqualTo("tenant-a");
  assertThat(actor.authorizationDecisionRef()).isEqualTo("fixture:40");
 }
 @Test void browserAndDelegatedReadAndConfirmationAllUseTheBoundOwnerContext()throws Exception{
  var packages=mock(ResolutionReviewPackageService.class);
  var api=new TrustedHumanApi(mock(IdentityGovernanceService.class),packages,mock(GovernedIdentityPreflight.class));
  api.reviewPackage(valid());api.delegatedReviewPackage(valid());
  api.confirmReviewPackage(new TrustedHumanApi.PackageConfirmationRequest("sha256:fixture",List.of()),valid());
  verify(packages,times(2)).prepare(argThat(a->a.actorType().equals("HUMAN")&&a.capabilities().equals(Set.of("resolution.issue.read"))));
  verify(packages).confirm(eq("sha256:fixture"),eq(List.of()),argThat(a->a.capabilities().equals(Set.of("resolution.match.approve"))));
 }
 @Test void missingAnyOpaqueLabelOrDetailCannotReadWholeMixedPackage()throws Exception{
  for(String missing:LABELS){var allowed=new HashSet<>(LABELS);allowed.remove(missing);
   var request=request("HUMAN","alice",CAPS,allowed,DETAILS,false);
   assertThatThrownBy(()->TrustedHumanApi.resolutionPackageActor(request,"resolution.issue.read"))
       .hasMessageContaining("NO_APPLICABLE_GRANT");
  }
  for(String missing:DETAILS){var allowed=new HashSet<>(DETAILS);allowed.remove(missing);
   var request=request("HUMAN","alice",CAPS,LABELS,allowed,false);
   assertThatThrownBy(()->TrustedHumanApi.resolutionPackageActor(request,"resolution.match.approve"))
       .hasMessageContaining("NO_APPLICABLE_GRANT");
  }
 }
 @Test void explicitDenyWrongSubjectAndMissingScopeRemainDenied()throws Exception{
  for(var request:List.of(request("HUMAN","alice",CAPS,LABELS,DETAILS,true),
      request("HUMAN","bob",CAPS,LABELS,DETAILS,false),
      request("HUMAN","alice",Set.of("resolution.match.approve"),LABELS,DETAILS,false)))
   assertThatThrownBy(()->TrustedHumanApi.resolutionPackageActor(request,"resolution.issue.read"))
       .isInstanceOf(SecurityException.class);
 }
 @Test void serviceAndAgentRemainForbiddenEvenWithScopesAndBroadGrant()throws Exception{
  for(String type:List.of("SERVICE","AI_AGENT")){
   var request=request(type,"alice",CAPS,LABELS,DETAILS,false);
   assertThatThrownBy(()->TrustedHumanApi.resolutionPackageActor(request,"resolution.issue.read"))
       .isInstanceOf(ResponseStatusException.class).hasMessageContaining("UDP_REVIEW_HUMAN_REQUIRED");
  }
 }
 @Test void callerCannotForgeActorTenantOrClassification()throws Exception{
  var request=valid();request.addHeader("X-Actor-Type","HUMAN");
  assertThatThrownBy(()->TrustedHumanApi.resolutionPackageActor(request,"resolution.issue.read"))
      .hasMessageContaining("UDP_UNTRUSTED_ACTOR_HEADER");
  var narrow=request("HUMAN","alice",CAPS,Set.of("OPEN"),Set.of("PUBLIC_OPERATIONAL"),false);
  narrow.addHeader("X-Data-Access-Label","OPEN");narrow.addParameter("detailLevel","PUBLIC_OPERATIONAL");
  assertThatThrownBy(()->TrustedHumanApi.resolutionPackageActor(narrow,"resolution.issue.read"))
      .hasMessageContaining("NO_APPLICABLE_GRANT");
 }
 @Test void missingOrRevokedPolicyCannotAuthorizeNewRequests()throws Exception{
  var missing=valid();missing.getServletContext().removeAttribute(ServletAuthorization.RUNTIME);
  assertThatThrownBy(()->TrustedHumanApi.resolutionPackageActor(missing,"resolution.issue.read"))
      .hasMessageContaining("NO_POLICY_BUNDLE");
  var request=valid();var runtime=(LocalAuthorization)request.getServletContext().getAttribute(ServletAuthorization.RUNTIME);
  TestAuthorization.install(runtime,new PolicyBundle("fixture",41,Instant.now(),List.of(),List.of()));
  assertThatThrownBy(()->TrustedHumanApi.resolutionPackageActor(request,"resolution.issue.read"))
      .hasMessageContaining("CAPABILITY_NOT_DECLARED");
 }
}
