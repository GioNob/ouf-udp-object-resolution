package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import it.comune.trieste.ouf.authorization.TestAuthorization;
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
        TestAuthorization.bind(request,"alice","HUMAN",Set.of("resolution.match.approve"));return request;
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
