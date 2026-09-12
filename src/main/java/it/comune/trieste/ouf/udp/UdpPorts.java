package it.comune.trieste.ouf.udp;

import java.util.*;

public final class UdpPorts {
  private UdpPorts(){}
  public interface ResolutionConfigurationPort {ResolutionProfile resolve(String bundleRef,String typeCode);}
  public interface MaterializationConfigurationPort {MaterializationProfile resolve(String bundleRef,String typeCode);}
  public record ResolutionProfile(String strategyId,String strategyVersion,String policyRef,String canonicalType,String canonicalKeyProperty,String matchProperty){}
  public record PropertyRule(String sourceField,String propertyIri,String datatype,String accessLabel,List<String> authorityOrder) {public PropertyRule{authorityOrder=List.copyOf(authorityOrder);}}
  public record MaterializationProfile(String policyRef,List<PropertyRule> properties) {public MaterializationProfile{properties=List.copyOf(properties);}}
}
