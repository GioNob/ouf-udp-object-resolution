package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Types;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RelationshipMaterializer {
  private final JdbcClient db;private final ObjectMapper json;
  public RelationshipMaterializer(JdbcClient db,ObjectMapper json){this.db=db;this.json=json;}

  @Transactional public Result materialize(String handoffId,UUID sourceObjectId,Map<String,Object> handoff,UdpPorts.RelationshipProfile profile){
    Map<String,Object> payload=HandoffIntakeService.object(handoff,"canonicalPayload");int matched=0,quarantined=0,skipped=0;
    for(var rule:profile.relationships()){
      if(!payload.containsKey(rule.sourceField()))continue;
      for(Object sourceValue:values(payload.get(rule.sourceField()))){
        String valueHash=hash(sourceValue);UUID contribution=contribution(handoffId,sourceObjectId,handoff,profile,rule,sourceValue,valueHash);
        List<UUID> candidates=candidates(rule,sourceValue);
        if(candidates.size()!=1){String reason=candidates.isEmpty()?"NO_MATCH":"MULTIPLE_MATCHES";if(candidates.isEmpty()&&!"QUARANTINE_RELATION".equals(rule.onNoMatch())){skipped++;continue;}issue(handoffId,contribution,sourceObjectId,rule,reason,candidates,valueHash);quarantined++;continue;}
        UUID target=candidates.getFirst();if(target.equals(sourceObjectId)&&!rule.selfLoopAllowed()){issue(handoffId,contribution,sourceObjectId,rule,"SELF_LOOP_NOT_ALLOWED",candidates,valueHash);quarantined++;continue;}
        edge(contribution,sourceObjectId,target,rule,handoff,valueHash);matched++;
      }
    }
    return new Result(matched,quarantined,skipped);
  }
  private UUID contribution(String handoff,UUID source,Map<String,Object> payload,UdpPorts.RelationshipProfile profile,UdpPorts.RelationshipRule rule,Object value,String hash){
    List<UUID> old=db.sql("select contribution_id from ouf_udp.relationship_contribution where handoff_id=:h and relation_iri=:r and source_value_hash=:x").param("h",handoff).param("r",rule.relationIri()).param("x",hash).query(UUID.class).list();if(!old.isEmpty())return old.getFirst();
    UUID id=UUID.randomUUID();Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("handoffRef","handoff://"+handoff);evidence.put("lineageRef",payload.get("lineageId"));evidence.put("contractRefs",payload.get("contractRefs"));evidence.put("sourceIdentity",payload.get("sourceIdentity"));
    db.sql("insert into ouf_udp.relationship_contribution(contribution_id,handoff_id,source_object_id,relation_iri,source_value,source_value_hash,strategy_ref,policy_ref,access_label,provenance_json) values(:i,:h,:s,:r,cast(:v as jsonb),:x,:t,:p,:a,cast(:e as jsonb))").param("i",id).param("h",handoff).param("s",source).param("r",rule.relationIri()).param("v",write(value)).param("x",hash).param("t",rule.resolutionStrategy()).param("p",profile.policyRef()).param("a",rule.accessLabel()).param("e",write(evidence)).update();return id;
  }
  private List<UUID> candidates(UdpPorts.RelationshipRule rule,Object value){String needle=String.valueOf(value).strip().toLowerCase(Locale.ROOT);return db.sql("select distinct o.urban_object_id from ouf_udp.urban_object o join ouf_udp.object_revision r on r.revision_id=o.current_revision_id where o.canonical_type=:t and o.status='ACTIVE' and lower(r.canonical_payload->>:p)=:v order by o.urban_object_id").param("t",rule.targetCanonicalType()).param("p",rule.targetPropertyIri()).param("v",needle).query(UUID.class).list();}
  private void edge(UUID contribution,UUID source,UUID target,UdpPorts.RelationshipRule rule,Map<String,Object> handoff,String valueHash){
    List<UUID> existing=db.sql("select relationship_id from ouf_udp.urban_relationship where source_object_id=:s and relation_iri=:r and target_object_id=:t").param("s",source).param("r",rule.relationIri()).param("t",target).query(UUID.class).list();UUID relationship=existing.isEmpty()?UUID.nameUUIDFromBytes((source+"|"+rule.relationIri()+"|"+target).getBytes(StandardCharsets.UTF_8)):existing.getFirst();if(existing.isEmpty())db.sql("insert into ouf_udp.urban_relationship(relationship_id,source_object_id,relation_iri,target_object_id) values(:i,:s,:r,:t)").param("i",relationship).param("s",source).param("r",rule.relationIri()).param("t",target).update();
    String evidenceHash=hash(Map.of("contribution",contribution,"target",target,"strategy",rule.resolutionStrategy()));List<UUID> old=db.sql("select relationship_revision_id from ouf_udp.relationship_revision where relationship_id=:r and evidence_hash=:h").param("r",relationship).param("h",evidenceHash).query(UUID.class).list();if(!old.isEmpty())return;UUID revision=UUID.randomUUID();long number=db.sql("select coalesce(max(revision_no),0)+1 from ouf_udp.relationship_revision where relationship_id=:r").param("r",relationship).query(Long.class).single();Map<String,Object> evidence=Map.of("sourceValueHash",valueHash,"targetObjectRef","urban-object://"+target,"strategy",rule.resolutionStrategy(),"handoffRef","handoff://"+handoff.get("handoffId"));db.sql("insert into ouf_udp.relationship_revision(relationship_revision_id,relationship_id,revision_no,contribution_id,evidence_hash,resolution_evidence,access_label) values(:i,:r,:n,:c,:h,cast(:e as jsonb),:a)").param("i",revision).param("r",relationship).param("n",number).param("c",contribution).param("h",evidenceHash).param("e",write(evidence)).param("a",rule.accessLabel()).update();db.sql("update ouf_udp.urban_relationship set current_revision_id=:v where relationship_id=:r").param("v",revision).param("r",relationship).update();
  }
  private void issue(String handoff,UUID contribution,UUID source,UdpPorts.RelationshipRule rule,String reason,List<UUID> candidates,String valueHash){long open=db.sql("select count(*) from ouf_udp.relationship_issue where source_object_id=:s and relation_iri=:r and reason_code=:c and state='OPEN'").param("s",source).param("r",rule.relationIri()).param("c",reason).query(Long.class).single();if(open>0)return;List<String> refs=candidates.stream().map(x->"urban-object://"+x).toList();db.sql("insert into ouf_udp.relationship_issue(issue_id,handoff_id,contribution_id,source_object_id,relation_iri,reason_code,on_no_match,candidate_refs,evidence_refs) values(gen_random_uuid(),:h,:c,:s,:r,:x,:o,cast(:a as jsonb),cast(:e as jsonb))").param("h",handoff).param("c",contribution).param("s",source).param("r",rule.relationIri()).param("x",reason).param("o",rule.onNoMatch()).param("a",write(refs)).param("e",write(List.of("source-value:"+valueHash,"strategy:"+rule.resolutionStrategy()))).update();}
  private static List<Object> values(Object v){return v instanceof List<?> l?new ArrayList<>(l):List.of(v);}
  private String hash(Object value){try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)));}catch(Exception e){throw new IllegalStateException(e);}}
  private String write(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalArgumentException("UDP_JSON_INVALID",e);}}
  public record Result(int matched,int quarantined,int skipped){}
}
