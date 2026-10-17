package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Exercise JSON conversion, not a direct Java service call. */
class ResolutionPackageHttpContractTest {
 @Test void browserSelectivePayloadReachesOwnerWithoutLosingHashOrOptionalNote()throws Exception{
  var packages=mock(ResolutionReviewPackageService.class);
  UUID id=UUID.randomUUID();String hash="sha256:"+"a".repeat(64);
  when(packages.confirm(anyString(),anyList(),any())).thenAnswer(call->{
    List<ResolutionReviewPackageService.Choice> choices=call.getArgument(1);
    assertThat(choices).hasSize(1);var choice=choices.getFirst();
    assertThat(choice.issueId()).isEqualTo(id);assertThat(choice.expectedVersion()).isZero();
    assertThat(choice.action()).isEqualTo("CREATE_NEW");assertThat(choice.reason()).isEmpty();
    assertThat(choice.targetUrbanObjectId()).isNull();assertThat(choice.expectedReviewHash()).isEqualTo(hash);
    return new ResolutionReviewPackageService.Confirmation(UUID.randomUUID(),hash,1);
  });
  var mvc=MockMvcBuilders.standaloneSetup(new TrustedHumanApi(mock(IdentityGovernanceService.class),packages,mock(GovernedIdentityPreflight.class))).setControllerAdvice(new ResolutionRequestErrors()).build();
  String body="{\"snapshotHash\":\""+hash+"\",\"choices\":[{\"issueId\":\""+id+"\",\"expectedVersion\":0,\"action\":\"CREATE_NEW\",\"reason\":\"\",\"targetUrbanObjectId\":null,\"expectedReviewHash\":\""+hash+"\"}]}";
  mvc.perform(post("/api/udp/v1/governance/resolution/issues/package/confirm")
      .contentType(MediaType.APPLICATION_JSON).content(body).with(request->{
        TestAuthorization.bind(request,"alice","HUMAN",Set.of("resolution.match.approve"));
        var now=Instant.now();var constraints=new GrantConstraints("ALLOW",null,null,null,Map.of(),
            Set.of("ANONYMOUS","OPEN","PERSONAL","RESTRICTED","SENSITIVE"),
            Set.of("PUBLIC_OPERATIONAL","TENANT_OPERATIONAL","RESTRICTED_OPERATIONAL","SECURITY_SENSITIVE"),null,Set.of(),null);
        try{TestAuthorization.install((LocalAuthorization)request.getServletContext().getAttribute(ServletAuthorization.RUNTIME),
            new PolicyBundle("fixture",40,now,List.of(new CapabilityDescriptor("resolution.match.approve","COMMAND","resolution.match.approve",Set.of(PrincipalContext.ActorType.HUMAN))),
                List.of(new Grant("allow","resolution.match.approve","tenant-a","alice",null,null,now.minusSeconds(60),now.plusSeconds(3600),constraints))));}catch(Exception e){throw new IllegalStateException(e);}
        return request;
      })).andExpect(status().isOk()).andExpect(jsonPath("$.issueCount").value(1));
  verify(packages).confirm(anyString(),anyList(),any());
 }
 @Test void malformedBrowserJsonIsRejectedBeforeAnyDecisionAndReturnsOnlyStableCode()throws Exception{
  var packages=mock(ResolutionReviewPackageService.class);
  var mvc=MockMvcBuilders.standaloneSetup(new TrustedHumanApi(mock(IdentityGovernanceService.class),packages,mock(GovernedIdentityPreflight.class)))
      .setControllerAdvice(new ResolutionRequestErrors()).build();
  mvc.perform(post("/api/udp/v1/governance/resolution/issues/package/confirm").contentType(MediaType.APPLICATION_JSON)
      .content("{\"choices\":[{\"issueId\":\"private-invalid-input\"}]}"))
      .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UDP_REVIEW_REQUEST_INVALID"));
  verifyNoInteractions(packages);
 }

}

