package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ObjectResolutionService {
  @org.springframework.beans.factory.annotation.Value("${ouf.udp.lake.tenant-id:default}") private String tenant;
  private final JdbcClient db;private final ObjectMapper json;public ObjectResolutionService(JdbcClient db,ObjectMapper json){this.db=db;this.json=json;}
  @Transactional public Decision resolve(String handoffId,Map<String,Object> payload,UdpPorts.ResolutionProfile profile){
    Map<String,Object> source=HandoffIntakeService.object(payload,"sourceIdentity"),canonical=HandoffIntakeService.object(payload,"canonicalPayload");
    db.sql("select pg_advisory_xact_lock(hashtextextended(:key,0))")
      .param("key","source-binding:"+write(List.of(HandoffIntakeService.text(source,"sourceId"),HandoffIntakeService.text(source,"typeCode"),HandoffIntakeService.text(source,"sourceObjectId")))).query().singleRow();
    List<Map<String,Object>> prior=db.sql("select d.outcome,coalesce(d.target_urban_object_id,b.urban_object_id) target_urban_object_id from ouf_udp.resolution_decision d left join ouf_udp.handoff_intake h on h.handoff_id=d.handoff_id left join ouf_udp.source_binding b on b.source_id=h.source_id and b.type_code=h.type_code and b.source_object_id=h.source_object_id where d.handoff_id=:h").param("h",handoffId).query().listOfRows();if(!prior.isEmpty()){
      var previous=prior.getFirst();String outcome=String.valueOf(previous.get("outcome"));
      UUID target=Set.of("MATCH","NEW_OBJECT").contains(outcome)?(UUID)previous.get("target_urban_object_id"):null;
      if(target!=null&&!inScope(target,profile))throw new SecurityException("UDP_RESOLUTION_REPLAY_TARGET_SCOPE_MISMATCH");
      return new Decision(outcome,target,true);
    }
    if(canonical==null)return rejected(handoffId,profile,"UDP_CANONICAL_PAYLOAD_REQUIRED");
    List<Map<String,Object>> bound=db.sql("select b.urban_object_id,u.tenant_id,u.canonical_type,u.status from ouf_udp.source_binding b join ouf_udp.urban_object u on u.urban_object_id=b.urban_object_id where b.source_id=:s and b.type_code=:t and b.source_object_id=:o and b.state='ACTIVE' for update of b,u")
      .param("s",HandoffIntakeService.text(source,"sourceId")).param("t",HandoffIntakeService.text(source,"typeCode")).param("o",HandoffIntakeService.text(source,"sourceObjectId")).query().listOfRows();
    if(bound.size()==1){
      var existing=bound.getFirst();
      if(!scopeMatches(existing,profile))return bindingReview(handoffId,profile);
      UUID target=(UUID)existing.get("urban_object_id");
      decision(handoffId,"MATCH",target,profile,List.of("source-binding://"+HandoffIntakeService.text(source,"sourceId")+"/"+HandoffIntakeService.text(source,"sourceObjectId")),1.0);
      return new Decision("MATCH",target,false);
    }
    String canonicalKey=value(canonical,profile.canonicalKeyProperty()),matchKey=value(canonical,profile.matchProperty());List<UUID> candidates=matchKey==null?List.of():db.sql("select urban_object_id from ouf_udp.urban_object where tenant_id=:tenant and canonical_type=:t and match_key=:m and status='ACTIVE' order by urban_object_id for update").param("tenant",tenant==null||tenant.isBlank()?"default":tenant).param("t",profile.canonicalType()).param("m",matchKey).query(UUID.class).list();List<String> evidence=List.of("handoff://"+handoffId,"match-key:sha256:"+hash(String.valueOf(matchKey)));
    if(candidates.size()>1){UUID id=decision(handoffId,"REVIEW_REQUIRED",null,profile,evidence,null);db.sql("insert into ouf_udp.resolution_issue(issue_id,resolution_decision_id,handoff_id,reason_code,candidate_refs,evidence_refs) values(gen_random_uuid(),:d,:h,'UDP_RESOLUTION_AMBIGUOUS',cast(:c as jsonb),cast(:e as jsonb))").param("d",id).param("h",handoffId).param("c",write(candidates)).param("e",write(evidence)).update();return new Decision("REVIEW_REQUIRED",null,false);}
    UUID target;if(candidates.size()==1){target=candidates.getFirst();decision(handoffId,"MATCH",target,profile,evidence,1.0);}else{target=UUID.randomUUID();db.sql("insert into ouf_udp.urban_object(urban_object_id,tenant_id,canonical_type,canonical_key,match_key) values(:i,:tenant,:t,:k,:m)").param("i",target).param("tenant",tenant==null||tenant.isBlank()?"default":tenant).param("t",profile.canonicalType()).param("k",canonicalKey).param("m",matchKey).update();decision(handoffId,"NEW_OBJECT",null,profile,evidence,1.0);}
    int bindingWritten=db.sql("insert into ouf_udp.source_binding(source_id,type_code,source_object_id,urban_object_id,first_handoff_id) values(:s,:t,:o,:u,:h) on conflict(source_id,type_code,source_object_id) do update set urban_object_id=excluded.urban_object_id,state='ACTIVE' where source_binding.state='UNRESOLVED'").param("s",HandoffIntakeService.text(source,"sourceId")).param("t",HandoffIntakeService.text(source,"typeCode")).param("o",HandoffIntakeService.text(source,"sourceObjectId")).param("u",target).param("h",handoffId).update();if(bindingWritten!=1)throw new IllegalStateException("UDP_SOURCE_BINDING_CHANGED_DURING_RESOLUTION");return new Decision(candidates.isEmpty()?"NEW_OBJECT":"MATCH",target,false);
  }
  private String effectiveTenant(){return tenant==null||tenant.isBlank()?"default":tenant;}
  private boolean scopeMatches(Map<String,Object> object,UdpPorts.ResolutionProfile profile){
    return effectiveTenant().equals(object.get("tenant_id"))&&profile.canonicalType().equals(object.get("canonical_type"))&&"ACTIVE".equals(object.get("status"));
  }
  private boolean inScope(UUID target,UdpPorts.ResolutionProfile profile){
    return db.sql("select tenant_id,canonical_type,status from ouf_udp.urban_object where urban_object_id=:u for update").param("u",target).query().listOfRows().stream().anyMatch(v->scopeMatches(v,profile));
  }
  private Decision bindingReview(String handoff,UdpPorts.ResolutionProfile profile){
    List<String> evidence=List.of("handoff://"+handoff,"validation://UDP_SOURCE_BINDING_SCOPE_MISMATCH");
    UUID id=decision(handoff,"REVIEW_REQUIRED",null,profile,evidence,null);
    db.sql("insert into ouf_udp.resolution_issue(issue_id,resolution_decision_id,handoff_id,reason_code,candidate_refs,evidence_refs) values(gen_random_uuid(),:d,:h,'UDP_SOURCE_BINDING_SCOPE_MISMATCH','[]'::jsonb,cast(:e as jsonb))").param("d",id).param("h",handoff).param("e",write(evidence)).update();
    return new Decision("REVIEW_REQUIRED",null,false);
  }
  private Decision rejected(String handoff,UdpPorts.ResolutionProfile p,String reason){decision(handoff,"REJECTED",null,p,List.of("validation://"+reason),null);return new Decision("REJECTED",null,false);}
  private UUID decision(String handoff,String outcome,UUID target,UdpPorts.ResolutionProfile p,List<String> evidence,Double confidence){UUID id=UUID.randomUUID();var spec=db.sql("insert into ouf_udp.resolution_decision(resolution_decision_id,handoff_id,candidate_ref,outcome,target_urban_object_id,strategy_id,strategy_version,evidence_refs,confidence,decided_by,policy_ref) values(:i,:h,:c,:o,:u,:s,:v,cast(:e as jsonb),:f,'SERVICE_IDENTITY',:p)").param("i",id).param("h",handoff).param("c","handoff://"+handoff).param("o",outcome).param("u",target,java.sql.Types.OTHER).param("s",p.strategyId()).param("v",p.strategyVersion()).param("e",write(evidence)).param("f",confidence,java.sql.Types.NUMERIC).param("p",p.policyRef());spec.update();return id;}
  private static String value(Map<String,Object> map,String key){Object v=key==null?null:map.get(key);return v==null||String.valueOf(v).isBlank()?null:String.valueOf(v).strip().toLowerCase(Locale.ROOT);}
  private String write(Object v){try{return json.writeValueAsString(v);}catch(JsonProcessingException e){throw new IllegalArgumentException("UDP_JSON_INVALID",e);}}
  private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
  public record Decision(String outcome,UUID targetUrbanObjectId,boolean duplicate){}
}
