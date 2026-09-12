package it.comune.trieste.ouf.udp;

import java.util.*;

public final class UdpPorts {
  private UdpPorts(){}
  public interface ResolutionConfigurationPort {ResolutionProfile resolve(String bundleRef,String typeCode);}
  public interface MaterializationConfigurationPort {MaterializationProfile resolve(String bundleRef,String typeCode);}
  public interface RelationshipConfigurationPort {RelationshipProfile resolve(String bundleRef,String typeCode);}
  public record ResolutionProfile(String strategyId,String strategyVersion,String policyRef,String canonicalType,String canonicalKeyProperty,String matchProperty){}
  public record PropertyRule(String sourceField,String propertyIri,String datatype,String accessLabel,List<String> authorityOrder) {public PropertyRule{authorityOrder=List.copyOf(authorityOrder);}}
  public record MaterializationProfile(String policyRef,List<PropertyRule> properties) {public MaterializationProfile{properties=List.copyOf(properties);}}
  public record RelationshipRule(String sourceField,String relationIri,String targetCanonicalType,String targetPropertyIri,String resolutionStrategy,String onNoMatch,String accessLabel,boolean selfLoopAllowed){}
  public record RelationshipProfile(String policyRef,List<RelationshipRule> relationships) {public RelationshipProfile{relationships=List.copyOf(relationships);}}
}
