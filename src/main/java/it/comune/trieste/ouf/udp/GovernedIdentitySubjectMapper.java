package it.comune.trieste.ouf.udp;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Projects an Ingestion canonical record through the published materialization mapping. */
public final class GovernedIdentitySubjectMapper {
  public GovernedIdentityEngine.Subject map(String handoffId,Map<String,Object> handoff,
      UdpPorts.MaterializationProfile materialization,GovernedIdentityEngine.Policy policy){
    if(handoffId==null||handoffId.isBlank()||handoff==null||materialization==null||policy==null)
      throw invalid();
    Map<String,Object> source=HandoffIntakeService.object(handoff,"sourceIdentity");
    Map<String,Object> canonical=HandoffIntakeService.object(handoff,"canonicalPayload");
    Map<String,Object> refs=HandoffIntakeService.object(handoff,"contractRefs");
    if(source==null||canonical==null||refs==null)throw invalid();
    Object publicationValue=refs.get("semanticPublicationSetRef");
    if(!policy.sourceId().equals(source.get("sourceId"))
        ||!(publicationValue instanceof String publication)||publication.isBlank())throw invalid();
    Map<String,String> fields=new HashMap<>();
    for(var rule:materialization.properties()){
      if(rule.propertyIri()==null||rule.propertyIri().isBlank()
          ||rule.sourceField()==null||rule.sourceField().isBlank())throw invalid();
      if(fields.putIfAbsent(rule.propertyIri(),rule.sourceField())!=null)throw invalid();
    }
    Map<String,GovernedIdentityEngine.Value> values=new HashMap<>();
    for(var signal:policy.signals()){
      String field=fields.get(signal.id());
      if(field==null||!signal.semanticRef().equals(signal.id()+"@"+publication))throw invalid();
      Object raw=canonical.get(field);
      if(canonical.containsKey(field)){
        String encoded;
        if(signal.comparator()==GovernedIdentityEngine.ComparatorKind.JSON_V1){
          try{encoded=new ObjectMapper().writeValueAsString(raw);}
          catch(Exception invalid){throw invalid();}
        }else if(raw instanceof String || raw instanceof Number)encoded=String.valueOf(raw);
        else throw invalid();
        values.put(signal.id(),new GovernedIdentityEngine.Value(signal.semanticRef(),encoded,
            "handoff://"+handoffId+"#canonicalPayload/"+field));
      }
    }
    return new GovernedIdentityEngine.Subject(policy.tenantId(),policy.canonicalClass(),policy.sourceId(),values);
  }
  private static IllegalArgumentException invalid(){return new IllegalArgumentException("UDP_IDENTITY_SUBJECT_MAPPING_INVALID");}
}
