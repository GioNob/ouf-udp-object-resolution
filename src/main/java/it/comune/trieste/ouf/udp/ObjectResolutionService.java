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
  private final JdbcClient db;private final ObjectMapper json;private final GovernedIdentityScopeLock identityScope;
  public ObjectResolutionService(JdbcClient db,ObjectMapper json,GovernedIdentityScopeLock identityScope){this.db=db;this.json=json;this.identityScope=identityScope;}
  @Transactional public Decision resolve(String handoffId,Map<String,Object> payload,UdpPorts.ResolutionProfile profile){
    return resolve(handoffId,payload,profile,null);
  }
  @Transactional public Decision resolve(String handoffId,Map<String,Object> payload,UdpPorts.ResolutionProfile profile,GovernedCrsTransform.Result geometry){
    identityScope.acquire(tenant==null||tenant.isBlank()?"default":tenant,profile.canonicalType());
    Map<String,Object> source=HandoffIntakeService.object(payload,"sourceIdentity"),canonical=HandoffIntakeService.object(payload,"canonicalPayload");
    GovernedIdentityScopeLock.lockBinding(db,source.get("sourceId"),source.get("typeCode"),source.get("sourceObjectId"));
    Map<String,Object> refresh=null;
    boolean createApproved=false;
    List<Map<String,Object>> prior=db.sql("select d.outcome,coalesce(d.target_urban_object_id,b.urban_object_id) target_urban_object_id,i.state issue_state,i.issue_id,i.version,i.tenant_id,i.reevaluation_pending,d.strategy_id,d.strategy_version,d.policy_ref,i.reason_code,b.state binding_state,b.first_handoff_id,hd.target_urban_object_id human_target,hd.action human_action from ouf_udp.resolution_decision d left join ouf_udp.handoff_intake h on h.handoff_id=d.handoff_id left join ouf_udp.source_binding b on b.source_id=h.source_id and b.type_code=h.type_code and b.source_object_id=h.source_object_id left join ouf_udp.resolution_issue i on i.resolution_decision_id=d.resolution_decision_id left join lateral (select action,target_urban_object_id from ouf_udp.human_resolution_decision where issue_id=i.issue_id order by decided_at desc,decision_id desc limit 1) hd on true where d.handoff_id=:h").param("h",handoffId).query().listOfRows();if(!prior.isEmpty()){
      var previous=prior.getFirst();String outcome=String.valueOf(previous.get("outcome"));
      boolean reviewed="REVIEW_REQUIRED".equals(outcome)&&"RESOLVED".equals(previous.get("issue_state"));
      UUID target=(reviewed||Set.of("MATCH","NEW_OBJECT").contains(outcome))?(UUID)previous.get("target_urban_object_id"):null;
      boolean active=target!=null&&"ACTIVE".equals(previous.get("binding_state"))&&inScope(target,profile);
      createApproved=reviewed&&"CREATE_NEW".equals(previous.get("human_action"));
      boolean changedApproval=reviewed&&!createApproved&&(!active||!target.equals(previous.get("human_target")));
      boolean pending="OPEN".equals(previous.get("issue_state"))&&Boolean.TRUE.equals(previous.get("reevaluation_pending"));
      if(!pending&&!createApproved&&!changedApproval){
        if(target!=null&&!active)throw new SecurityException("UDP_RESOLUTION_REPLAY_TARGET_SCOPE_MISMATCH");
        return new Decision(reviewed&&target!=null?"MATCH":outcome,target,true);
      }
      if(!profile.strategyId().equals(previous.get("strategy_id"))||!profile.strategyVersion().equals(previous.get("strategy_version"))
          ||!profile.policyRef().equals(previous.get("policy_ref"))||!effectiveTenant().equals(previous.get("tenant_id")))
        throw new IllegalStateException("UDP_IDENTITY_DECISION_CONFLICT");
      if(createApproved&&active&&handoffId.equals(previous.get("first_handoff_id")))return new Decision("NEW_OBJECT",target,true);
      refresh=previous;
    }
    if(canonical==null)return rejected(handoffId,profile,"UDP_CANONICAL_PAYLOAD_REQUIRED");
    List<Map<String,Object>> bound=db.sql("select b.urban_object_id,b.state binding_state,u.tenant_id,u.canonical_type,u.status from ouf_udp.source_binding b join ouf_udp.urban_object u on u.urban_object_id=b.urban_object_id where b.source_id=:s and b.type_code=:t and b.source_object_id=:o for update of b,u")
      .param("s",HandoffIntakeService.text(source,"sourceId")).param("t",HandoffIntakeService.text(source,"typeCode")).param("o",HandoffIntakeService.text(source,"sourceObjectId")).query().listOfRows();
    if(bound.size()==1){
      var existing=bound.getFirst();
      boolean active="ACTIVE".equals(existing.get("binding_state"))&&scopeMatches(existing,profile);
      if(refresh!=null&&(active||!"UNRESOLVED".equals(existing.get("binding_state"))||createApproved))
        return refreshed(refresh,active?"ACTIVE_SOURCE_BINDING":"UDP_SOURCE_BINDING_SCOPE_MISMATCH",
            active?List.of((UUID)existing.get("urban_object_id")):List.of(),scopeEvidence(handoffId,profile,"validation://binding"));
      if(!active&&!"UNRESOLVED".equals(existing.get("binding_state")))return bindingReview(handoffId,profile);
      if(active){
        UUID target=(UUID)existing.get("urban_object_id");
        decision(handoffId,"MATCH",target,profile,List.of("source-binding://"+HandoffIntakeService.text(source,"sourceId")+"/"+HandoffIntakeService.text(source,"sourceObjectId")),1.0);
        return new Decision("MATCH",target,false);
      }
    }
    String canonicalKey=value(canonical,profile.canonicalKeyProperty()),matchKey=value(canonical,profile.matchProperty());
    var keyQuery=db.sql("""
        select urban_object_id,canonical_key,match_key from ouf_udp.urban_object
        where tenant_id=:tenant and canonical_type=:type and status='ACTIVE'
          and (match_key=:match or canonical_key=:key) order by urban_object_id
        """+(profile.weighted()==null?"":" limit :limit")+" for update").param("tenant",effectiveTenant()).param("type",profile.canonicalType())
        .param("match",matchKey,java.sql.Types.VARCHAR).param("key",canonicalKey,java.sql.Types.VARCHAR);
    if(profile.weighted()!=null)keyQuery.param("limit",profile.weighted().maxCandidates()+1);
    var keys=keyQuery.query().listOfRows();
    List<UUID> candidates=keys.stream().map(row->(UUID)row.get("urban_object_id")).toList();
    List<String> evidence=scopeEvidence(handoffId,profile,"match-key:sha256:"+hash(String.valueOf(matchKey)));
    WeightedIdentity.Outcome scored=null;
    String reviewReason=null;
    if(profile.weighted()!=null){
      scored=new WeightedIdentityCandidates(db,json).resolve(canonical,profile,effectiveTenant(),geometry);
      if("REVIEW_REQUIRED".equals(scored.outcome()))reviewReason=scored.reason();
      else if(!candidates.isEmpty()&&(candidates.size()!=1||!candidates.getFirst().equals(scored.target())))
        reviewReason="UDP_RESOLUTION_KEY_CONFLICT";
      if(reviewReason==null)candidates=scored.target()==null?List.of():List.of(scored.target());
      else if("RESOLUTION_TOO_BROAD".equals(reviewReason))candidates=List.of();
      else {
        Set<UUID> alternatives=new TreeSet<>(candidates);
        scored.candidates().forEach(candidate->alternatives.add(candidate.id()));
        candidates=List.copyOf(alternatives);
        if(candidates.size()>profile.weighted().maxCandidates()){
          candidates=List.of();reviewReason="RESOLUTION_TOO_BROAD";
        }
      }
    }else if(keys.size()>1)reviewReason="UDP_RESOLUTION_AMBIGUOUS";
    else if(keys.size()==1&&(!Objects.equals(keys.getFirst().get("canonical_key"),canonicalKey)
        ||!Objects.equals(keys.getFirst().get("match_key"),matchKey)))reviewReason="UDP_RESOLUTION_KEY_CONFLICT";
    if(refresh!=null&&reviewReason==null&&candidates.isEmpty()&&bound.isEmpty()&&reservedKey(profile,canonicalKey))
      reviewReason="UDP_RESOLUTION_KEY_CONFLICT";
    if(refresh!=null&&(!createApproved||reviewReason!=null||!candidates.isEmpty()||!bound.isEmpty())){
      String reason=reviewReason!=null?reviewReason:candidates.isEmpty()&&bound.isEmpty()
          ?"UDP_LEGACY_NEW_IDENTITY_AVAILABLE":candidates.isEmpty()?"UDP_SOURCE_BINDING_SCOPE_MISMATCH":"UDP_RESOLUTION_REVIEW_REQUIRED";
      return refreshed(refresh,reason,candidates,evidence);
    }
    UUID target=candidates.size()==1?candidates.getFirst():null;
    if(reviewReason==null&&target==null){
      target=UUID.randomUUID();
      int inserted=db.sql("""
          insert into ouf_udp.urban_object(urban_object_id,tenant_id,canonical_type,canonical_key,match_key)
          values(:id,:tenant,:type,:key,:match) on conflict do nothing
          """).param("id",target).param("tenant",effectiveTenant()).param("type",profile.canonicalType())
          .param("key",canonicalKey,java.sql.Types.VARCHAR).param("match",matchKey,java.sql.Types.VARCHAR).update();
      if(inserted==0){
        // The bridge still reserves keys belonging to inactive objects and other tenants.
        // Never expose the owner as a possible match.
        if(!reservedKey(profile,canonicalKey))throw new IllegalStateException("UDP_IDENTITY_DECISION_CONFLICT");
        reviewReason="UDP_RESOLUTION_KEY_CONFLICT";
        if(refresh!=null)return refreshed(refresh,reviewReason,List.of(),evidence);
      }
    }
    if(reviewReason!=null){
      UUID id=decision(handoffId,"REVIEW_REQUIRED",null,profile,evidence,null,scored);
      db.sql("insert into ouf_udp.resolution_issue(issue_id,resolution_decision_id,handoff_id,reason_code,candidate_refs,evidence_refs,tenant_id) values(gen_random_uuid(),:d,:h,:reason,cast(:c as jsonb),cast(:e as jsonb),:tenant)")
          .param("d",id).param("h",handoffId).param("reason",reviewReason).param("c",write(candidates))
          .param("e",write(evidence)).param("tenant",effectiveTenant()).update();
      return new Decision("REVIEW_REQUIRED",null,false);
    }
    if(candidates.size()==1)decision(handoffId,"MATCH",target,profile,evidence,scored==null?1.0:scored.candidates().getFirst().score(),scored);
    else if(!createApproved)decision(handoffId,"NEW_OBJECT",null,profile,evidence,scored==null?1.0:null,scored);
    int bindingWritten=db.sql("insert into ouf_udp.source_binding(source_id,type_code,source_object_id,urban_object_id,first_handoff_id) values(:s,:t,:o,:u,:h) on conflict(source_id,type_code,source_object_id) do update set urban_object_id=excluded.urban_object_id,state='ACTIVE' where source_binding.state='UNRESOLVED'").param("s",HandoffIntakeService.text(source,"sourceId")).param("t",HandoffIntakeService.text(source,"typeCode")).param("o",HandoffIntakeService.text(source,"sourceObjectId")).param("u",target).param("h",handoffId).update();if(bindingWritten!=1)throw new IllegalStateException("UDP_SOURCE_BINDING_CHANGED_DURING_RESOLUTION");return new Decision(candidates.isEmpty()?"NEW_OBJECT":"MATCH",target,false);
  }
  private Decision refreshed(Map<String,Object> issue,String reason,List<UUID> candidates,List<String> evidence){
    int changed=db.sql("update ouf_udp.resolution_issue set state='OPEN',version=version+1,reevaluation_pending=false,reason_code=:r,candidate_refs=cast(:c as jsonb),evidence_refs=cast(:e as jsonb),resolved_at=null,resolved_by_subject=null,resolution_reason=null,authorization_decision_ref=null where issue_id=:id and tenant_id=:tenant and state=:state and version=:v")
        .param("r",reason).param("c",write(candidates)).param("e",write(evidence)).param("id",issue.get("issue_id"))
        .param("tenant",effectiveTenant()).param("state",issue.get("issue_state")).param("v",issue.get("version")).update();
    if(changed!=1)throw new IllegalStateException("UDP_ISSUE_VERSION_CONFLICT");
    return new Decision("REVIEW_REQUIRED",null,false);
  }
  private String effectiveTenant(){return tenant==null||tenant.isBlank()?"default":tenant;}
  private boolean reservedKey(UdpPorts.ResolutionProfile profile,String key){
    return key!=null&&db.sql("select exists(select 1 from ouf_udp.urban_object where canonical_type=:type and canonical_key=:key)")
        .param("type",profile.canonicalType()).param("key",key).query(Boolean.class).single();
  }
  private boolean scopeMatches(Map<String,Object> object,UdpPorts.ResolutionProfile profile){
    return effectiveTenant().equals(object.get("tenant_id"))&&profile.canonicalType().equals(object.get("canonical_type"))&&"ACTIVE".equals(object.get("status"));
  }
  private boolean inScope(UUID target,UdpPorts.ResolutionProfile profile){
    return db.sql("select tenant_id,canonical_type,status from ouf_udp.urban_object where urban_object_id=:u for update").param("u",target).query().listOfRows().stream().anyMatch(v->scopeMatches(v,profile));
  }
  private Decision bindingReview(String handoff,UdpPorts.ResolutionProfile profile){
    List<String> evidence=scopeEvidence(handoff,profile,"validation://UDP_SOURCE_BINDING_SCOPE_MISMATCH");
    UUID id=decision(handoff,"REVIEW_REQUIRED",null,profile,evidence,null);
    db.sql("insert into ouf_udp.resolution_issue(issue_id,resolution_decision_id,handoff_id,reason_code,candidate_refs,evidence_refs,tenant_id) values(gen_random_uuid(),:d,:h,'UDP_SOURCE_BINDING_SCOPE_MISMATCH','[]'::jsonb,cast(:e as jsonb),:tenant)").param("d",id).param("h",handoff).param("e",write(evidence)).param("tenant",effectiveTenant()).update();
    return new Decision("REVIEW_REQUIRED",null,false);
  }
  private List<String> scopeEvidence(String handoff,UdpPorts.ResolutionProfile p,String reason){return List.of("handoff://"+handoff,reason,"scope:tenant:"+effectiveTenant(),"scope:canonical-type:"+p.canonicalType());}
  private Decision rejected(String handoff,UdpPorts.ResolutionProfile p,String reason){decision(handoff,"REJECTED",null,p,List.of("validation://"+reason),null);return new Decision("REJECTED",null,false);}
  private UUID decision(String handoff,String outcome,UUID target,UdpPorts.ResolutionProfile p,List<String> evidence,Double confidence){return decision(handoff,outcome,target,p,evidence,confidence,null);}
  private UUID decision(String handoff,String outcome,UUID target,UdpPorts.ResolutionProfile p,List<String> evidence,Double confidence,WeightedIdentity.Outcome scored){UUID id=UUID.randomUUID();var spec=db.sql("insert into ouf_udp.resolution_decision(resolution_decision_id,handoff_id,candidate_ref,outcome,target_urban_object_id,strategy_id,strategy_version,evidence_refs,confidence,decided_by,policy_ref,score_evidence) values(:i,:h,:c,:o,:u,:s,:v,cast(:e as jsonb),:f,'SERVICE_IDENTITY',:p,cast(:score as jsonb))").param("i",id).param("h",handoff).param("c","handoff://"+handoff).param("o",outcome).param("u",target,java.sql.Types.OTHER).param("s",p.strategyId()).param("v",p.strategyVersion()).param("e",write(evidence)).param("f",confidence,java.sql.Types.NUMERIC).param("p",p.policyRef()).param("score",scored==null?null:write(scored),java.sql.Types.VARCHAR);spec.update();return id;}
  private static String value(Map<String,Object> map,String key){Object v=key==null?null:map.get(key);return v==null||String.valueOf(v).isBlank()?null:String.valueOf(v).strip().toLowerCase(Locale.ROOT);}
  private String write(Object v){try{return json.writeValueAsString(v);}catch(JsonProcessingException e){throw new IllegalArgumentException("UDP_JSON_INVALID",e);}}
  private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
  public record Decision(String outcome,UUID targetUrbanObjectId,boolean duplicate){}
}
