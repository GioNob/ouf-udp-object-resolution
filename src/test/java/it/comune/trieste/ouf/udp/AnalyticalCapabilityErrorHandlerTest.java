package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AnalyticalCapabilityErrorHandlerTest {
  @Test
  void handlerReturnsNonRetryableMachineReadableResponseWithoutRedirect() {
    var body = new CapabilityRouter.AnalyticalCapabilityResponse(
        "QUERY_REQUIRES_ANALYTICAL_CAPABILITY", "urban.graph.traverse",
        "COMPOSITE_ANALYTICAL_PATTERN_OUTSIDE_OPERATIONAL_SERVING",
        "ANALYTICAL_OR_GOVERNED_ASYNC_JOB", false, false,
        List.of("NARROW_TO_OPERATIONAL_QUERY", "STOP"),
        new CapabilityRouter.BudgetImpact("NONE", "ONE_ANALYTICAL_REJECTION"));
    var response = new CapabilityRouterErrorHandler()
        .analytical(new AnalyticalCapabilityRequiredException(body));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getHeaders().getLocation()).isNull();
    assertThat(response.getBody()).isEqualTo(body);
  }
}
