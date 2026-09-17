package it.comune.trieste.ouf.udp;

import java.util.*;

public final class UdpPorts {
  private UdpPorts(){}
  public interface ResolutionConfigurationPort {ResolutionProfile resolve(String bundleRef,String typeCode);}
  public interface MaterializationConfigurationPort {MaterializationProfile resolve(String bundleRef,String typeCode);}
  public interface RelationshipConfigurationPort {RelationshipProfile resolve(String bundleRef,String typeCode);}
  public interface SpatialConfigurationPort {SpatialProfile resolve(String bundleRef,String typeCode);}
  public record ResolutionProfile(String strategyId,String strategyVersion,String policyRef,String canonicalType,String canonicalKeyProperty,String matchProperty,WeightedIdentity.Policy weighted){
    public ResolutionProfile(String strategyId,String strategyVersion,String policyRef,String canonicalType,String canonicalKeyProperty,String matchProperty){this(strategyId,strategyVersion,policyRef,canonicalType,canonicalKeyProperty,matchProperty,null);}
  }
  public record PropertyRule(String sourceField,String propertyIri,String datatype,String accessLabel,List<String> authorityOrder) {public PropertyRule{authorityOrder=List.copyOf(authorityOrder);}}
  public record MaterializationProfile(String policyRef,List<PropertyRule> properties,Set<String> bitemporalProperties,int checkpointInterval) {
    public MaterializationProfile(String policyRef,List<PropertyRule> properties){this(policyRef,properties,Set.of(),1);}
    public MaterializationProfile{properties=List.copyOf(properties);bitemporalProperties=Set.copyOf(bitemporalProperties);if(checkpointInterval<1)throw new IllegalArgumentException("UDP_CHECKPOINT_INTERVAL_INVALID");}
  }
  public record RelationshipRule(String sourceField,String relationIri,String targetCanonicalType,String targetPropertyIri,String resolutionStrategy,String onNoMatch,String accessLabel,boolean selfLoopAllowed){}
  public record RelationshipProfile(String policyRef,List<RelationshipRule> relationships) {public RelationshipProfile{relationships=List.copyOf(relationships);}}
  public record GeometryRule(String sourceField,String expectedSourceCrs,int canonicalSrid,String normalizationVersion,String accessLabel,CrsPolicy crsPolicy){
    public GeometryRule(String sourceField,String expectedSourceCrs,int canonicalSrid,String normalizationVersion,String accessLabel){this(sourceField,expectedSourceCrs,canonicalSrid,normalizationVersion,accessLabel,null);}
  }
  public record CrsPolicy(String sourceAxisOrder,String mismatchAction,CrsOperation sourceOperation,CrsOperation servingOperation){}
  public record CrsControlPoint(double sourceX,double sourceY,double targetX,double targetY,double tolerance){}
  public record CrsOperation(String operationId,int sourceSrid,int targetSrid,String pipeline,String projVersion,
      Double accuracyMeters,String accuracyStatement,List<Double> sourceBounds,List<CrsControlPoint> controlPoints,
      Map<String,String> requiredGrids,String resourceVersion){}
  public record SpatialRelationshipRule(String relationIri,String targetCanonicalType,String predicate,String onNoMatch,Double maxDistanceMeters,Double minOverlapRatio,Double rankingMarginMeters,String accessLabel,boolean selfLoopAllowed){}
  public record SpatialProfile(String policyRef,GeometryRule geometry,List<SpatialRelationshipRule> relationships) {public SpatialProfile{relationships=List.copyOf(relationships);}}
}
