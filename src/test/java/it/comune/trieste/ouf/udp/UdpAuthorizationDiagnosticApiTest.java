package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.assertThat;

import it.comune.trieste.ouf.authorization.LocalAuthorization;
import it.comune.trieste.ouf.authorization.ServletAuthorization;
import it.comune.trieste.ouf.authorization.TestAuthorization;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class UdpAuthorizationDiagnosticApiTest {
  @Test void reportsOnlyDecisionMetadataForVerifiedPrincipal() {
    var request = new MockHttpServletRequest();
    TestAuthorization.bind(request, "private-test-subject", "HUMAN",
        Set.of("urban.identity.preflight"));
    var engine = (LocalAuthorization) request.getServletContext()
        .getAttribute(ServletAuthorization.RUNTIME);
    var answer = new UdpAuthorizationDiagnosticApi(engine).inspect(request);
    assertThat(answer).containsEntry("bundleVersion", 1L)
        .containsEntry("actorType", "HUMAN")
        .containsEntry("decisionCode", "ALLOW")
        .containsEntry("allowed", true);
    assertThat(answer.toString()).doesNotContain("private-test-subject");
  }
}
