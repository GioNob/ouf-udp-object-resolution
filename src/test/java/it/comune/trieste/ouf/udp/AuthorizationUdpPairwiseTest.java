package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthorizationUdpPairwiseTest {
 @Test void canonicalHumanCrossesAdapterAndLegacyDomainInputIsBounded(){
  var r=new MockHttpServletRequest();TestAuthorization.bind(r,"alice","HUMAN",Set.of("urban.object.merge"));
  var actor=TrustedHumanApi.trusted(r);actor.require("urban.object.merge");
  assertThat(actor.actorType()).isEqualTo("HUMAN");assertThat(actor.authorizationDecisionRef()).isEqualTo("fixture:1");
  new TrustedHumanContext("HUMAN_USER","historical","tenant-a",Set.of("read"),"historical:decision","corr").require("read");
 }
 @Test void coarseAllowCannotOverrideMissingLocalGrant(){
  var r=new MockHttpServletRequest();TestAuthorization.bind(r,"alice","HUMAN",Set.of("urban.object.read"));
  r.setAttribute("ouf.capabilities",Set.of("urban.object.merge"));r.setAttribute("ouf.authorizationDecisionRef","coarse:allow");
  assertThatThrownBy(()->TrustedHumanApi.trusted(r).require("urban.object.merge")).hasMessage("UDP_TRUSTED_HUMAN_REQUIRED");
  ServingApi.context(r).require("urban.object.read");
 }
 @Test void serviceAndAgentCannotApproveEvenWithCoarseAndLocalCapability(){
  for(String type:List.of("SERVICE","AI_AGENT")){var r=new MockHttpServletRequest();TestAuthorization.bind(r,"machine",type,Set.of("urban.object.merge"));assertThatThrownBy(()->TrustedHumanApi.trusted(r).require("urban.object.merge")).hasMessage("UDP_TRUSTED_HUMAN_REQUIRED");}
 }
 @Test void subjectAttributesAndHeadersCannotImpersonatePrincipal(){
  var r=new MockHttpServletRequest();r.setUserPrincipal(()->"legacy");r.setAttribute("ouf.actorType","HUMAN_USER");r.setAttribute("ouf.subject","alice");r.setAttribute("ouf.capabilities",Set.of("urban.object.read"));
  assertThatThrownBy(()->TrustedHumanApi.trusted(r)).hasMessageContaining("TRUSTED_PRINCIPAL_REQUIRED");
  r.addHeader("X-Actor-Type","HUMAN");assertThatThrownBy(()->TrustedHumanApi.trusted(r)).hasMessageContaining("UDP_UNTRUSTED_ACTOR_HEADER");
 }
 @Test void missingBundleFailsClosed(){var r=new MockHttpServletRequest();r.setUserPrincipal(TestAuthorization.principal("alice","HUMAN",Set.of("urban.object.read")));assertThatThrownBy(()->ServingApi.context(r)).hasMessageContaining("NO_POLICY_BUNDLE");}
 @Test void requestPinsDecisionWhileNextRequestObservesRevocation()throws Exception{
  var r=new MockHttpServletRequest();TestAuthorization.bind(r,"alice","HUMAN",Set.of("urban.object.read"));
  var first=ServingApi.context(r);first.require("urban.object.read");
  var runtime=(LocalAuthorization)r.getServletContext().getAttribute(ServletAuthorization.RUNTIME);
  TestAuthorization.install(runtime,new PolicyBundle("fixture",2,Instant.now(),List.of(),List.of()));
  ServingApi.context(r).require("urban.object.read");
  var next=new MockHttpServletRequest(r.getServletContext());next.setUserPrincipal(r.getUserPrincipal());
  assertThatThrownBy(()->ServingApi.context(next).require("urban.object.read")).hasMessage("UDP_AUTHORIZATION_DENIED");
  assertThat(ServingApi.context(next).authorizationDecisionRef()).isEqualTo("fixture:2");
 }
}
