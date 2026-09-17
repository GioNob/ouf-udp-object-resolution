package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Human choices are scoped to an exact evidence set and a pinned authority policy. */
@Service
public class PropertyGovernanceService {
  private final JdbcClient db;
  private final ObjectMapper json;
  private final CanonicalMaterializer materializer;
  public PropertyGovernanceService(JdbcClient db,ObjectMapper json,CanonicalMaterializer materializer){this.db=db;this.json=json;this.materializer=materializer;}

  @Transactional(readOnly=true)
  public Map<String,Object> review(UUID id,ServingAuthorizationContext auth){
    auth.require("resolution.issue.read");var issue=issue(id,false);UUID object=(UUID)issue.get("urban_object_id");
    UUID current=object(object,auth,false);var candidates=candidates(issue,auth,"resolution.issue.read");
    return Map.of("conflictId",id,"objectId",object,"currentRevision",current,"state",issue.get("state"),"propertyIri",issue.get("property_iri"),"policyRef",issue.get("policy_ref"),"evidenceHash",issue.get("evidence_hash"),"candidates",candidates);
  }

  @Transactional
  public UUID decide(UUID id,UUID expectedRevision,UUID chosen,String reason,TrustedHumanContext actor,ServingAuthorizationContext auth){
    actor.require("authority.override");auth.require("authority.override");
    if(!Set.of("HUMAN","HUMAN_USER").contains(auth.principalType())||!actor.subject().equals(auth.subject())||!actor.tenantId().equals(auth.tenantId()))throw new SecurityException("UDP_AUTHORIZATION_CONTEXT_MISMATCH");
    if(reason==null||reason.isBlank()||reason.length()>2000)throw new IllegalArgumentException("UDP_REASON_REQUIRED");
    var initial=issue(id,false);UUID object=(UUID)initial.get("urban_object_id");
    UUID current=object(object,auth,true);var issue=issue(id,true);
    candidates(issue,auth,"authority.override");
    var existing=db.sql("select * from ouf_udp.human_property_decision where conflict_id=:i").param("i",id).query().listOfRows();
    if(!existing.isEmpty()){
      var old=existing.getFirst();
      if(Objects.equals(chosen,old.get("chosen_contribution"))&&Objects.equals(expectedRevision,old.get("expected_revision"))&&actor.subject().equals(old.get("actor_subject"))&&reason.equals(old.get("reason")))return (UUID)old.get("decision_id");
      throw conflict();
    }
    if(!"OPEN".equals(issue.get("state"))||!Objects.equals(current,expectedRevision))throw conflict();
    UdpPorts.MaterializationProfile profile=read(String.valueOf(issue.get("profile")),UdpPorts.MaterializationProfile.class);
    var rule=profile.properties().stream().filter(p->p.propertyIri().equals(issue.get("property_iri"))).findFirst().orElseThrow(PropertyGovernanceService::conflict);
    var top=PropertyEvidence.top(db,object,rule);
    if(!PropertyEvidence.set(top).equals(issue.get("evidence_hash")))throw conflict();
    var selected=top.stream().filter(r->Objects.equals(chosen,r.get("contribution_id"))).findFirst().orElseThrow(PropertyGovernanceService::conflict);
    var currentPolicy=db.sql("select authority_state->:property->>'decisionRef' from ouf_udp.urban_object_current_state where urban_object_id=:u").param("property",rule.propertyIri()).param("u",object).query(String.class).optional();
    if(currentPolicy.isPresent()&&!currentPolicy.get().equals(profile.policyRef())&&!currentPolicy.get().startsWith("property-decision://"))throw conflict();
    UUID decision=UUID.randomUUID();
    db.sql("insert into ouf_udp.human_property_decision(decision_id,conflict_id,urban_object_id,property_iri,policy_ref,evidence_hash,chosen_contribution,chosen_evidence_hash,expected_revision,actor_subject,authorization_decision_ref,reason,correlation_id) values(:d,:i,:u,:p,:policy,:hash,:chosen,:choice,:revision,:actor,:auth,:reason,:correlation)")
      .param("d",decision).param("i",id).param("u",object).param("p",rule.propertyIri()).param("policy",profile.policyRef()).param("hash",issue.get("evidence_hash")).param("chosen",chosen).param("choice",PropertyEvidence.item(selected)).param("revision",expectedRevision).param("actor",actor.subject()).param("auth",auth.decisionRef("authority.override")).param("reason",reason).param("correlation",actor.correlationId()).update();
    var handoff=db.sql("select h.handoff_id,h.payload_json::text payload from ouf_udp.property_contribution c join ouf_udp.handoff_intake h on h.handoff_id=c.handoff_id where c.contribution_id=:c").param("c",chosen).query().singleRow();
    // Recompute only the decided property; an older snapshot must not roll back unrelated policy changes.
    var scoped=new UdpPorts.MaterializationProfile(profile.policyRef(),List.of(rule),profile.bitemporalProperties().contains(rule.propertyIri())?Set.of(rule.propertyIri()):Set.of(),profile.checkpointInterval());
    materializer.applyAuthorityDecision(String.valueOf(handoff.get("handoff_id")),object,map(String.valueOf(handoff.get("payload"))),scoped);
    db.sql("update ouf_udp.property_conflict set state='RESOLVED' where conflict_id=:i").param("i",id).update();
    db.sql("insert into ouf_udp.governance_audit(audit_id,action,outcome,actor_type,actor_subject,tenant_id,capability,authorization_decision_ref,reason,correlation_id,evidence_hash) values(gen_random_uuid(),'UDP_AUTHORITY_OVERRIDE','APPROVED','HUMAN',:a,:tenant,'authority.override',:auth,:reason,:correlation,:evidence)")
      .param("a",actor.subject()).param("tenant",actor.tenantId()).param("auth",auth.decisionRef("authority.override")).param("reason",reason).param("correlation",actor.correlationId()).param("evidence",issue.get("evidence_hash")).update();
    return decision;
  }

  private Map<String,Object> issue(UUID id,boolean lock){return db.sql("select *,materialization_profile::text profile,contribution_refs::text refs from ouf_udp.property_conflict where conflict_id=:i and evidence_hash is not null"+(lock?" for update":"")).param("i",id).query().listOfRows().stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"UDP_PROPERTY_CONFLICT_NOT_FOUND"));}
  private UUID object(UUID id,ServingAuthorizationContext auth,boolean lock){return db.sql("select current_revision_id from ouf_udp.urban_object where urban_object_id=:u and tenant_id=:t and status='ACTIVE'"+(lock?" for update":"")).param("u",id).param("t",auth.tenantId()).query(UUID.class).optional().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"UDP_OBJECT_NOT_FOUND"));}
  private List<Map<String,Object>> candidates(Map<String,Object> issue,ServingAuthorizationContext auth,String capability){
    var rows=db.sql("select c.contribution_id,c.source_id,c.access_label,c.value_json::text value,c.provenance_json::text provenance from ouf_udp.property_contribution c where c.urban_object_id=:u and c.property_iri=:p and c.contribution_id in (select value::uuid from jsonb_array_elements_text(cast(:refs as jsonb))) order by c.source_id,c.contribution_id")
      .param("u",issue.get("urban_object_id")).param("p",issue.get("property_iri")).param("refs",issue.get("refs")).query().listOfRows();
    List<Map<String,Object>> result=new ArrayList<>();
    for(var row:rows){
      String label=String.valueOf(row.get("access_label"));var scope=Map.of("sourceRef",String.valueOf(row.get("source_id")),"propertyRef",String.valueOf(issue.get("property_iri")),"projection","properties");
      if(!auth.allowedDataLabels().contains(label))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"UDP_PROPERTY_PROFILE_LABEL_DENIED");
      auth.requireResource(capability,"object",issue.get("urban_object_id"),label,scope);auth.requireResource("urban.object.read","object",issue.get("urban_object_id"),label,scope);
      Object value=read(String.valueOf(row.get("value")),Object.class);
      if(value instanceof Map<?,?> m&&m.containsKey("geoJson")&&m.containsKey("crs"))throw new ResponseStatusException(HttpStatus.CONFLICT,"UDP_GEOMETRY_REVIEW_REQUIRED");
      var item=new LinkedHashMap<String,Object>();item.put("contributionId",row.get("contribution_id"));item.put("sourceId",row.get("source_id"));item.put("value",value);item.put("provenance",map(String.valueOf(row.get("provenance"))));result.add(item);
    }
    return result;
  }
  private <T>T read(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("UDP_STORED_JSON_INVALID",e);}}
  private Map<String,Object> map(String value){try{return json.readValue(value,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException("UDP_STORED_JSON_INVALID",e);}}
  private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"UDP_PROPERTY_DECISION_CONFLICT");}
}
