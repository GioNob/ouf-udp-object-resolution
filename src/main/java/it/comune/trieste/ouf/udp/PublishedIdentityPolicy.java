package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Strict decoder for the proposed historical policy shape. Does not authorize execution. */
public final class PublishedIdentityPolicy {
  private static final Set<String> RESOLUTION=Set.of("strategyId","strategyVersion","policyRef","governedIdentity");
  private static final Set<String> POLICY=Set.of("ref","version","tenantId","canonicalClass","sourceId",
      "maxCandidates","allowAutoNew","signals","sufficientRules");
  private static final Set<String> SIGNAL=Set.of("id","semanticRef","comparator",
      "excludesOnDisagreement","uniqueWithinScope","assertionRef");
  private static final Set<String> RULE=Set.of("id","signalIds","assertionRef");
  private PublishedIdentityPolicy() {}

  public static GovernedIdentityEngine.Policy decode(ObjectMapper json, Map<String,Object> resolution,
      String tenant, String source, String canonicalClass, Set<String> mappedProperties) {
    if(json==null || resolution==null || !resolution.keySet().equals(RESOLUTION)
        || !"GOVERNED_IDENTITY".equals(resolution.get("strategyId")))throw invalid();
    Map<?,?> raw=map(resolution.get("governedIdentity"),POLICY);
    if(!Objects.equals(raw.get("ref"),resolution.get("policyRef"))
        || !Objects.equals(raw.get("version"),resolution.get("strategyVersion"))
        || !Objects.equals(raw.get("tenantId"),tenant)
        || !Objects.equals(raw.get("sourceId"),source)
        || !Objects.equals(raw.get("canonicalClass"),canonicalClass)
        || !(raw.get("maxCandidates") instanceof Number max) || max.doubleValue()!=max.intValue()
        || !(raw.get("allowAutoNew") instanceof Boolean))throw invalid();
    List<?> signals=list(raw.get("signals"),1,32),rules=list(raw.get("sufficientRules"),1,1);
    Set<String> compared=new HashSet<>();
    for(Object item:signals){
      Map<?,?> signal=map(item,SIGNAL);
      if(!(signal.get("id") instanceof String id) || mappedProperties==null || !mappedProperties.contains(id)
          || !(signal.get("semanticRef") instanceof String ref) || !ref.matches("[^@\\s]+@[^@\\s]+")
          || !(signal.get("assertionRef") instanceof String assertion) || assertion.isBlank()
          || !Boolean.FALSE.equals(signal.get("excludesOnDisagreement"))
          || !Boolean.FALSE.equals(signal.get("uniqueWithinScope")) || !compared.add(id))throw invalid();
    }
    if(!compared.equals(mappedProperties))throw invalid();
    for(Object item:rules){
      Map<?,?> rule=map(item,RULE);
      if(!(rule.get("signalIds") instanceof List<?> ids) || !new HashSet<>(ids).equals(compared)
          || ids.size()!=compared.size())throw invalid();
    }
    try{return json.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,true)
        .convertValue(raw,GovernedIdentityEngine.Policy.class);
    }catch(IllegalArgumentException failure){throw invalid();}
  }
  private static Map<?,?> map(Object value,Set<String> fields){
    if(!(value instanceof Map<?,?> map)||!map.keySet().equals(fields))throw invalid();return map;
  }
  private static List<?> list(Object value,int min,int max){
    if(!(value instanceof List<?> list)||list.size()<min||list.size()>max)throw invalid();return list;
  }
  private static IllegalArgumentException invalid(){return new IllegalArgumentException("UDP_GOVERNED_IDENTITY_PROFILE_INVALID");}
}
