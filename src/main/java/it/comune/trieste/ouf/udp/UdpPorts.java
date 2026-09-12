package it.comune.trieste.ouf.udp;

public final class UdpPorts {
  private UdpPorts(){}
  public interface ResolutionConfigurationPort {ResolutionProfile resolve(String bundleRef,String typeCode);}
  public record ResolutionProfile(String strategyId,String strategyVersion,String policyRef,String canonicalType,String canonicalKeyProperty,String matchProperty){}
}
