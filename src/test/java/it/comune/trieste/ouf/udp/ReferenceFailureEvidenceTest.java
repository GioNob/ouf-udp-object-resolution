package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReferenceFailureEvidenceTest {
  @Test void retainsKnownCodeAndBoundedApplicationFrames(){
    var error=new IllegalArgumentException("UDP_PINNED_PROFILE_INVALID");
    error.setStackTrace(new StackTraceElement[]{
        new StackTraceElement("com.fasterxml.jackson.Mapper","parse","Mapper.java",5),
        new StackTraceElement("it.comune.trieste.ouf.udp.PublishedRuntimeConfiguration","resolve","Profile.java",42)});
    assertThat(ReferenceFailureEvidence.detail(error)).containsEntry("diagnosticCode","UDP_PINNED_PROFILE_INVALID")
        .containsEntry("diagnosticFrames",List.of("it.comune.trieste.ouf.udp.PublishedRuntimeConfiguration#resolve:42"));
  }
  @Test void nullAndArbitraryMessagesAreNeverRetained(){
    for(String message:Arrays.asList(null,"secret-token and payload","UDP_PINNED_PROFILE_INVALID: private")){
      var detail=ReferenceFailureEvidence.detail(new IllegalArgumentException(message));
      assertThat(detail).containsEntry("diagnosticCode","UNCLASSIFIED");
      assertThat(detail.toString()).doesNotContain("secret-token","private");
    }
  }
}
