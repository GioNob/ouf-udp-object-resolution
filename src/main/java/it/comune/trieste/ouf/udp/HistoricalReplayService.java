package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Types;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HistoricalReplayService {
  private final JdbcClient db;private final ObjectMapper json;private final HistoricalContractCatalog catalog;private final org.springframework.beans.factory.ObjectProvider<LakeObjectStoragePort> storage;private final boolean executionEnabled;
  public HistoricalReplayService(JdbcClient db,ObjectMapper json,HistoricalContractCatalog catalog,org.springframework.beans.factory.ObjectProvider<LakeObjectStoragePort> storage,@org.springframework.beans.factory.annotation.Value("${ouf.udp.execution.enabled:false}") boolean executionEnabled){this.db=db;this.json=json;this.catalog=catalog;this.storage=storage;this.executionEnabled=executionEnabled;}

  @Transactional public Plan plan(String sourceHandoffId,String reason,TrustedHumanContext actor){
    actor.require("udp.replay.plan");required(reason,"UDP_REPLAY_REASON_REQUIRED");Map<String,Object> source=source(sourceHandoffId,actor.tenantId());Map<String,Object> payload=readMap(String.valueOf(source.get("payload_json")));Map<String,Object> refs=HandoffIntakeService.object(payload,"contractRefs");var resolution=catalog.resolve(refs);UUID id=UUID.randomUUID();String state=resolution.ready()?"READY":"PAUSED";String baseline=write(refs);
    db.sql("insert into ouf_udp.replay_plan(replay_plan_id,tenant_id,source_handoff_id,raw_lake_object_id,mode,state,baseline_refs,baseline_hash,source_payload_hash,missing_ref_hashes,reason,created_by_subject,authorization_decision_ref,correlation_id) values(:id,:tenant,:source,:raw,'REPRODUCE',:state,cast(:refs as jsonb),:baseline,:payload,cast(:missing as jsonb),:reason,:subject,:decision,:correlation)")
      .param("id",id).param("tenant",actor.tenantId()).param("source",sourceHandoffId).param("raw",source.get("raw_lake_object_id")).param("state",state).param("refs",baseline).param("baseline",resolution.baselineHash(),Types.VARCHAR).param("payload",source.get("content_hash")).param("missing",write(resolution.missingRefHashes())).param("reason",reason).param("subject",actor.subject()).param("decision",actor.authorizationDecisionRef()).param("correlation",actor.correlationId()).update();
    event(id,state.equals("READY")?"REPRODUCE_PLANNED":"REPRODUCE_PAUSED",actor,Map.of("missingRefHashes",resolution.missingRefHashes(),"baselineHash",Objects.toString(resolution.baselineHash(),"")));return get(id,actor.tenantId());
  }

  @Transactional public Plan resume(UUID planId,long expectedVersion,TrustedHumanContext actor){
    actor.require("udp.replay.plan");Plan plan=get(planId,actor.tenantId());if(!"PAUSED".equals(plan.state())||plan.version()!=expectedVersion)conflict();var resolution=catalog.resolve(plan.baselineRefs());if(!resolution.ready()){event(planId,"REPRODUCE_RECHECK_FAILED",actor,Map.of("missingRefHashes",resolution.missingRefHashes()));return plan;}
    int changed=db.sql("update ouf_udp.replay_plan set state='READY',version=version+1,baseline_hash=:hash,missing_ref_hashes='[]'::jsonb where replay_plan_id=:id and tenant_id=:tenant and state='PAUSED' and version=:version")
      .param("hash",resolution.baselineHash()).param("id",planId).param("tenant",actor.tenantId()).param("version",expectedVersion).update();if(changed!=1)conflict();event(planId,"REPRODUCE_RESUMED",actor,Map.of("baselineHash",resolution.baselineHash()));return get(planId,actor.tenantId());
  }

  @Transactional public Plan execute(UUID planId,long expectedVersion,TrustedHumanContext actor){
    actor.require("udp.replay.execute");Plan plan=get(planId,actor.tenantId());if("SUCCEEDED".equals(plan.state()))return plan;if(!"READY".equals(plan.state())||plan.version()!=expectedVersion)conflict();var resolution=catalog.resolve(plan.baselineRefs());if(!resolution.ready()||!Objects.equals(plan.baselineHash(),resolution.baselineHash())){
      List<String> missing=resolution.ready()?List.of(HistoricalContractCatalog.hash("BASELINE_CHANGED")):resolution.missingRefHashes();db.sql("update ouf_udp.replay_plan set state='PAUSED',version=version+1,baseline_hash=null,missing_ref_hashes=cast(:missing as jsonb) where replay_plan_id=:id and tenant_id=:tenant and state='READY' and version=:version").param("missing",write(missing)).param("id",planId).param("tenant",actor.tenantId()).param("version",expectedVersion).update();event(planId,"REPRODUCE_PAUSED",actor,Map.of("missingRefHashes",missing));return get(planId,actor.tenantId());}
    Map<String,Object> source=source(plan.sourceHandoffId(),actor.tenantId());Map<String,Object> payload=readMap(String.valueOf(source.get("payload_json")));if(!Objects.equals(plan.sourcePayloadHash(),source.get("content_hash"))||!Objects.equals(plan.baselineRefs(),HandoffIntakeService.object(payload,"contractRefs")))throw new IllegalStateException("UDP_REPLAY_SOURCE_CHANGED");
    String replayHandoff="replay-"+planId;String replayRun="reproduce-"+planId;payload.put("handoffId",replayHandoff);payload.put("ingestionRunId",replayRun);payload.put("ingestionId",replayRun);Map<String,Object> identity=HandoffIntakeService.object(payload,"sourceIdentity");
    db.sql("insert into ouf_udp.handoff_intake(handoff_id,ingestion_run_id,ingestion_id,source_id,type_code,source_object_id,content_hash,payload_json,receipt_ref,raw_lake_object_id) values(:h,:r,:i,:s,:t,:o,:c,cast(:p as jsonb),:receipt,:raw)")
      .param("h",replayHandoff).param("r",replayRun).param("i",replayRun).param("s",HandoffIntakeService.text(identity,"sourceId")).param("t",HandoffIntakeService.text(identity,"typeCode")).param("o",HandoffIntakeService.text(identity,"sourceObjectId")).param("c",plan.sourcePayloadHash()).param("p",write(payload)).param("receipt","udp://handoffs/"+replayHandoff).param("raw",plan.rawLakeObjectId()).update();
    if(source.get("source_raw_lake_object_id")!=null){
      UUID sourceRaw=(UUID)source.get("source_raw_lake_object_id");
      db.sql("update ouf_udp.handoff_intake set source_raw_lake_object_id=:raw where handoff_id=:h").param("raw",sourceRaw).param("h",replayHandoff).update();
      db.sql("update ouf_udp.lake_object set blocking_lineage_refs=blocking_lineage_refs+1,state_version=state_version+1 where lake_object_id=:raw").param("raw",sourceRaw).update();
    }
    db.sql("insert into ouf_udp.materialization_job(job_id,handoff_id) values(gen_random_uuid(),:h)").param("h",replayHandoff).update();db.sql("insert into ouf_udp.handoff_event(event_id,handoff_id,event_type,safe_detail) values(gen_random_uuid(),:h,'REPRODUCE_DURABLE',cast(:detail as jsonb))").param("h",replayHandoff).param("detail",write(Map.of("replayPlanId",planId.toString(),"sourceHandoffId",plan.sourceHandoffId(),"baselineHash",plan.baselineHash()))).update();
    int changed=db.sql("update ouf_udp.replay_plan set state='SUCCEEDED',version=version+1,replay_handoff_id=:handoff,completed_at=transaction_timestamp() where replay_plan_id=:id and tenant_id=:tenant and state='READY' and version=:version").param("handoff",replayHandoff).param("id",planId).param("tenant",actor.tenantId()).param("version",expectedVersion).update();if(changed!=1)conflict();event(planId,"REPRODUCE_EXECUTED",actor,Map.of("replayHandoffId",replayHandoff,"baselineHash",plan.baselineHash()));return get(planId,actor.tenantId());
  }

  @Transactional public Plan abort(UUID planId,long expectedVersion,String reason,TrustedHumanContext actor){actor.require("udp.replay.execute");required(reason,"UDP_REPLAY_REASON_REQUIRED");Plan plan=get(planId,actor.tenantId());if(!Set.of("READY","PAUSED").contains(plan.state())||plan.version()!=expectedVersion)conflict();int changed=db.sql("update ouf_udp.replay_plan set state='ABORTED',version=version+1,aborted_at=transaction_timestamp() where replay_plan_id=:id and tenant_id=:tenant and state in('READY','PAUSED') and version=:version").param("id",planId).param("tenant",actor.tenantId()).param("version",expectedVersion).update();if(changed!=1)conflict();event(planId,"REPRODUCE_ABORTED",actor,Map.of("reason",reason));return get(planId,actor.tenantId());}

  public Plan get(UUID id,String tenant){List<Map<String,Object>> rows=db.sql("select replay_plan_id,tenant_id,source_handoff_id,raw_lake_object_id,mode,state,version,baseline_refs::text baseline_refs,baseline_hash,source_payload_hash,missing_ref_hashes::text missing_ref_hashes,reason,replay_handoff_id,created_at,completed_at,aborted_at from ouf_udp.replay_plan where replay_plan_id=:id and tenant_id=:tenant").param("id",id).param("tenant",tenant).query().listOfRows();if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"UDP_REPLAY_PLAN_NOT_FOUND");Map<String,Object> r=rows.getFirst();return new Plan((UUID)r.get("replay_plan_id"),String.valueOf(r.get("source_handoff_id")),(UUID)r.get("raw_lake_object_id"),String.valueOf(r.get("mode")),String.valueOf(r.get("state")),((Number)r.get("version")).longValue(),readMap(String.valueOf(r.get("baseline_refs"))),Objects.toString(r.get("baseline_hash"),null),String.valueOf(r.get("source_payload_hash")),readList(String.valueOf(r.get("missing_ref_hashes"))),Objects.toString(r.get("replay_handoff_id"),null));}
  private Map<String,Object> source(String handoff,String tenant){List<Map<String,Object>> rows=db.sql("select h.payload_json::text payload_json,h.content_hash,h.raw_lake_object_id,h.source_raw_lake_object_id,l.content_hash lake_hash,l.logical_size_bytes,l.locator from ouf_udp.handoff_intake h join ouf_udp.lake_object l on l.lake_object_id=h.raw_lake_object_id where h.handoff_id=:h and l.tenant_id=:tenant and l.tier='RAW' and l.state in('VERIFIED','COMPACTED','COLD') and (:execution or l.content_hash=h.content_hash)").param("h",handoff).param("tenant",tenant).param("execution",executionEnabled).query().listOfRows();if(rows.isEmpty())throw new IllegalStateException("UDP_REPLAY_RAW_NOT_DURABLE");var row=rows.getFirst();
    if(executionEnabled){
      long size=((Number)row.get("logical_size_bytes")).longValue();if(size<1||size>10_485_760)throw new IllegalStateException("UDP_REPLAY_RAW_SIZE_INVALID");
      var port=storage.getIfAvailable();if(port==null)throw new IllegalStateException("UDP_REPLAY_STORAGE_UNAVAILABLE");
      byte[] bytes=port.read(String.valueOf(row.get("locator")));
      verifyRaw(json,bytes,size,String.valueOf(row.get("lake_hash")),String.valueOf(row.get("payload_json")));
    }
    return row;}
  static void verifyRaw(ObjectMapper json,byte[] bytes,long expectedSize,String expectedHash,String expectedPayload){
    try{String hash="sha256:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
      if(bytes.length!=expectedSize||!hash.equals(expectedHash)||!json.readTree(bytes).equals(json.readTree(expectedPayload)))throw new IllegalStateException("UDP_REPLAY_RAW_MISMATCH");
    }catch(java.io.IOException|java.security.NoSuchAlgorithmException e){throw new IllegalStateException("UDP_REPLAY_RAW_INVALID",e);}
  }
  private void event(UUID plan,String type,TrustedHumanContext actor,Map<String,Object> detail){db.sql("insert into ouf_udp.replay_event(event_id,replay_plan_id,event_type,actor_type,actor_subject,authorization_decision_ref,correlation_id,safe_detail) values(gen_random_uuid(),:plan,:type,:actor,:subject,:decision,:correlation,cast(:detail as jsonb))").param("plan",plan).param("type",type).param("actor",actor.actorType()).param("subject",actor.subject()).param("decision",actor.authorizationDecisionRef()).param("correlation",actor.correlationId()).param("detail",write(detail)).update();}
  private static void conflict(){throw new ResponseStatusException(HttpStatus.CONFLICT,"UDP_REPLAY_VERSION_CONFLICT");}
  private static void required(String value,String code){if(value==null||value.isBlank())throw new IllegalArgumentException(code);}
  private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("UDP_JSON_INVALID",e);}}
  private Map<String,Object> readMap(String value){try{return json.readValue(value,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException("UDP_STORED_JSON_INVALID",e);}}
  private List<String> readList(String value){try{return json.readValue(value,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException("UDP_STORED_JSON_INVALID",e);}}
  public record Plan(UUID replayPlanId,String sourceHandoffId,UUID rawLakeObjectId,String mode,String state,long version,Map<String,Object> baselineRefs,String baselineHash,String sourcePayloadHash,List<String> missingRefHashes,String replayHandoffId){}
}
