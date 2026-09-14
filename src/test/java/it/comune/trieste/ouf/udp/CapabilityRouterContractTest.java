package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CapabilityRouterContractTest {
  @Test
  void handlerReturnsMachineReadableUnprocessableEntityWithoutRedirect() {
    var remediation = new CapabilityRouter.Remediation(
        "QUERY_CAPABILITY_MISMATCH", "urban.graph.traverse",
        "CROSS_DOMAIN_NON_RECURSIVE_PATTERN", "urban.object.related_search", true,
        java.util.List.of("startObjectId", "relationTypes"),
        java.util.List.of("anchorType", "targetTypes", "direction"),
        "capability://urban.object.related_search/input-schema",
        new CapabilityRouter.BudgetImpact("NONE", "ONE_MISMATCH"));
    var response = new CapabilityRouterErrorHandler()
        .mismatch(new CapabilityMismatchException(remediation));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getHeaders().getLocation()).isNull();
    assertThat(response.getBody()).isEqualTo(remediation);
  }
}
