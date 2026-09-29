package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.assertThat;

import it.comune.trieste.ouf.authorization.LocalAuthorization;
import it.comune.trieste.ouf.authorization.ServletAuthorization;
import it.comune.trieste.ouf.authorization.TestAuthorization;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;

class UdpAuthorizationDiagnosticApiTest {
  @Test void remainsDisabledUnlessClonePropertyIsExplicitlyEnabled() {
    var context = new ApplicationContextRunner()
        .withBean(LocalAuthorization.class, () -> TestAuthorization.runtime(
            "fixture", "HUMAN", Set.of("urban.identity.preflight")))
        .withUserConfiguration(UdpAuthorizationDiagnosticApi.class);
    context.run(beans -> assertThat(beans).doesNotHaveBean(UdpAuthorizationDiagnosticApi.class));
    context.withPropertyValues("ouf.udp.authorization.diagnostic.enabled=true")
        .run(beans -> assertThat(beans).hasSingleBean(UdpAuthorizationDiagnosticApi.class));
  }

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
