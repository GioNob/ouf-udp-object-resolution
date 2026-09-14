package it.comune.trieste.ouf.udp;

public class AnalyticalCapabilityRequiredException extends RuntimeException {
  private final CapabilityRouter.AnalyticalCapabilityResponse response;

  public AnalyticalCapabilityRequiredException(
      CapabilityRouter.AnalyticalCapabilityResponse response) {
    super(response.code());
    this.response = response;
  }

  public CapabilityRouter.AnalyticalCapabilityResponse response() { return response; }
}
