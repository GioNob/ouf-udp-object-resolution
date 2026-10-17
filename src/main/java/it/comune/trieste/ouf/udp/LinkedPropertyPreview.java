package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Read-only preview over the owner-pinned profile; never resolves a new remote profile in a review transaction. */
final class LinkedPropertyPreview {
  private LinkedPropertyPreview(){}
  static void remember(JdbcClient db,String handoff,String type,String bundle,UdpPorts.MaterializationProfile profile){
    if(profile.properties().stream().noneMatch(r->r.reconciliation()!=null))return;
    String hash=LinkedPropertyReconciler.hash(profile);
    db.sql("insert into ouf_udp.handoff_property_profile(handoff_id,canonical_type,bundle_ref,profile,profile_hash) values(:h,:t,:b,cast(:p as jsonb),:hash) on conflict do nothing").param("h",handoff).param("t",type).param("b",bundle).param("p",LinkedPropertyReconciler.write(profile)).param("hash",hash).update();
    if(db.sql("select 1 from ouf_udp.handoff_property_profile where handoff_id=:h and canonical_type=:t and bundle_ref=:b and profile_hash=:hash").param("h",handoff).param("t",type).param("b",bundle).param("hash",hash).query(Integer.class).optional().isEmpty())throw new IllegalStateException("UDP_PROPERTY_PROFILE_DRIFT");
  }
  @SuppressWarnings("unchecked") static Map<String,Object> prepare(JdbcClient db,ObjectMapper json,String handoff,String target,String tenant){
    var profiles=db.sql("select p.profile::text profile,p.canonical_type,h.payload_json::text payload from ouf_udp.handoff_property_profile p join ouf_udp.handoff_intake h on h.handoff_id=p.handoff_id where p.handoff_id=:h").param("h",handoff).query().listOfRows();
    if(profiles.isEmpty())return Map.of("available",false,"reason","PINNED_PROPERTY_PROFILE_UNAVAILABLE");
    UUID object;try{object=UUID.fromString(target);}catch(IllegalArgumentException e){return Map.of("available",false,"reason","CANDIDATE_UNAVAILABLE");}
    var pinned=profiles.getFirst();
    var state=db.sql("select s.revision_id,s.canonical_payload::text payload from ouf_udp.urban_object o left join ouf_udp.urban_object_current_state s on s.urban_object_id=o.urban_object_id and s.revision_id=o.current_revision_id where o.urban_object_id=:u and o.tenant_id=:tenant and o.canonical_type=:type and o.status='ACTIVE'").param("u",object).param("tenant",tenant).param("type",pinned.get("canonical_type")).query().listOfRows();
    if(state.isEmpty())return Map.of("available",false,"reason","CANDIDATE_UNAVAILABLE");
    try{
      var profile=json.readValue(String.valueOf(pinned.get("profile")),UdpPorts.MaterializationProfile.class);
      var envelope=(Map<String,Object>)LinkedPropertyReconciler.decode(pinned.get("payload"));
      var current=state.getFirst().get("payload")==null?Map.<String,Object>of():(Map<String,Object>)LinkedPropertyReconciler.decode(state.getFirst().get("payload"));
      var properties=new LinkedHashMap<String,Object>();var now=PropertyEvidence.now(db);
      for(var rule:profile.properties()){
        if(rule.reconciliation()==null){properties.put(rule.propertyIri(),Map.of("available",false,"status","DEDICATED_PROPERTY_POLICY"));continue;}
        var rows=new ArrayList<>(PropertyEvidence.all(db,object,rule));var incoming=LinkedPropertyReconciler.incoming(handoff,envelope,rule);
        if(incoming!=null){incoming.put("source_identity",db.sql("select cast(:identity as jsonb)::text").param("identity",incoming.get("source_identity")).query(String.class).single());rows.add(incoming);}
        var top=LinkedPropertyReconciler.top(LinkedPropertyReconciler.eligible(rows,rule,now),rule);
        var selected=db.sql("select chosen_evidence_hash from ouf_udp.human_property_decision where urban_object_id=:u and property_iri=:p and policy_ref=:policy and evidence_hash=:hash order by decided_at desc limit 1").param("u",object).param("p",rule.propertyIri()).param("policy",profile.policyRef()).param("hash",PropertyEvidence.set(top,rule)).query(String.class).optional();
        UUID choice=selected.isEmpty()?null:top.stream().filter(r->PropertyEvidence.item(r).equals(selected.orElseThrow())).map(r->(UUID)r.get("contribution_id")).findFirst().orElse(null);
        var result=LinkedPropertyReconciler.reconcile(rows,rule,current.get(rule.propertyIri()),now,choice);
        var property=new LinkedHashMap<String,Object>();property.put("available",true);property.put("currentValue",current.get(rule.propertyIri()));property.put("incomingValue",HandoffIntakeService.object(envelope,"canonicalPayload").get(rule.sourceField()));property.put("proposedValue",result.value());property.put("status",result.status());property.put("conflict","CONFLICT".equals(result.status()));property.put("rule",rule);property.put("members",result.members());property.put("evidenceHash",result.evidenceHash());
        // All source rows remain inspectable, even when a lower priority does not win.
        property.put("contributions",rows.stream().map(r->Map.of("sourceId",r.get("source_id"),"accessLabel",r.get("access_label"),"provenance",LinkedPropertyReconciler.provenance(r),"valueJson",r.get("value_json"))).toList());
        properties.put(rule.propertyIri(),property);
      }
      var result=new LinkedHashMap<String,Object>();result.put("available",true);result.put("policyRef",profile.policyRef());result.put("currentRevision",state.getFirst().get("revision_id"));result.put("properties",properties);return result;
    }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("UDP_PROPERTY_PROFILE_INVALID",e);}
  }
}
