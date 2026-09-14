package it.comune.trieste.ouf.udp;

public class CapabilityMismatchException extends RuntimeException {
  private final CapabilityRouter.Remediation remediation;

  public CapabilityMismatchException(CapabilityRouter.Remediation remediation) {
    super(remediation.code());
    this.remediation = remediation;
  }

  public CapabilityRouter.Remediation remediation() { return remediation; }
}
