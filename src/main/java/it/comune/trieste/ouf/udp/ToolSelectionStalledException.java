package it.comune.trieste.ouf.udp;

public class ToolSelectionStalledException extends RuntimeException {
  private final CapabilityRouter.StalledResponse response;

  public ToolSelectionStalledException(CapabilityRouter.StalledResponse response) {
    super(response.code());
    this.response = response;
  }

  public CapabilityRouter.StalledResponse response() { return response; }
}
