package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HandoffIntakeService {
  @Value("${ouf.udp.execution.enabled:false}") private boolean executionEnabled;
  @org.springframework.beans.factory.annotation.Autowired private LakeCatalogRepository lakeCatalog;
  private final JdbcClient db;private final ObjectMapper json;private final FrozenContractValidator contracts;private final LakeLifecycleService lake;private final TransactionTemplate transactions;private final boolean lakeRequired;private final int rawRetentionDays;private final String rawRetentionClass;private final String rawAccessLabel;private final String tenantId;
  public HandoffIntakeService(JdbcClient db,ObjectMapper json,FrozenContractValidator contracts,LakeLifecycleService lake,TransactionTemplate transactions,@Value("${ouf.udp.lake.required:true}") boolean lakeRequired,@Value("${ouf.udp.lake.raw-retention-days:0}") int rawRetentionDays,@Value("${ouf.udp.lake.raw-retention-class:}") String rawRetentionClass,@Value("${ouf.udp.lake.raw-access-label:}") String rawAccessLabel,@Value("${ouf.udp.lake.tenant-id:}") String tenantId){this.db=db;this.json=json;this.contracts=contracts;this.lake=lake;this.transactions=transactions;this.lakeRequired=lakeRequired;this.rawRetentionDays=rawRetentionDays;this.rawRetentionClass=rawRetentionClass;this.rawAccessLabel=rawAccessLabel;this.tenantId=tenantId;}
  public Receipt accept(Map<String,Object> payload){
    contracts.validate("/contracts/rc3/ingestion/handoff-payload-v1.json",payload);boolean api=payload.containsKey("apiObservation");
    if(api){
      ApiObservation.parse(payload);
      if(tenantId.isBlank() || (payload.containsKey("tenantId") && !tenantId.equals(payload.get("tenantId"))))
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"UDP_API_TENANT_MISMATCH");
    }
    String id=text(payload,"handoffId"),hash=text(payload,"contentHash");Map<String,Object> identity=object(payload,"sourceIdentity");String receipt="udp://handoffs/"+id;List<Map<String,Object>> prior=db.sql("select content_hash,receipt_ref,(payload_json=cast(:payload as jsonb) and (not :api or exists(select 1 from ouf_udp.materialization_job j where j.handoff_id=h.handoff_id and coalesce(j.api_tenant_id,:tenant)=:tenant))) payload_matches from ouf_udp.handoff_intake h where h.handoff_id=:h").param("h",id).param("payload",write(payload)).param("api",api).param("tenant",tenantId).query().listOfRows();if(!prior.isEmpty())return duplicate(hash,prior.getFirst());String encoded=write(payload);SourceRaw sourceRaw=executionEnabled?verifySourceRaw(payload,identity):null;UUID rawLakeObject=null;if(lakeRequired){if(rawRetentionDays<=0||rawRetentionClass.isBlank()||rawAccessLabel.isBlank()||tenantId.isBlank())throw new IllegalStateException("UDP_LAKE_RETENTION_POLICY_REQUIRED");var raw=lake.store(encoded.getBytes(StandardCharsets.UTF_8),tenantId,text(identity,"sourceId"),text(identity,"typeCode"),"RAW","application/json",rawRetentionClass,rawAccessLabel,OffsetDateTime.now().plusDays(rawRetentionDays),String.valueOf(payload.getOrDefault("correlationId",id)));if(!"VERIFIED".equals(raw.state()))throw new IllegalStateException("UDP_LAKE_RAW_NOT_VERIFIED");rawLakeObject=raw.id();}UUID finalRawLakeObject=rawLakeObject;return transactions.execute(status->{if(sourceRaw!=null)lockSourceRaw(sourceRaw);int inserted=db.sql("insert into ouf_udp.handoff_intake(handoff_id,ingestion_run_id,ingestion_id,source_id,type_code,source_object_id,content_hash,payload_json,receipt_ref,raw_lake_object_id) values(:h,:r,:i,:s,:t,:o,:c,cast(:p as jsonb),:x,:lake) on conflict(handoff_id) do nothing").param("h",id).param("r",text(payload,"ingestionRunId")).param("i",text(payload,"ingestionId")).param("s",text(identity,"sourceId")).param("t",text(identity,"typeCode")).param("o",text(identity,"sourceObjectId")).param("c",hash).param("p",encoded).param("x",receipt).param("lake",finalRawLakeObject,Types.OTHER).update();if(inserted==0)return duplicate(hash,db.sql("select content_hash,receipt_ref,(payload_json=cast(:payload as jsonb) and (not :api or exists(select 1 from ouf_udp.materialization_job j where j.handoff_id=h.handoff_id and coalesce(j.api_tenant_id,:tenant)=:tenant))) payload_matches from ouf_udp.handoff_intake h where h.handoff_id=:h").param("h",id).param("payload",write(payload)).param("api",api).param("tenant",tenantId).query().singleRow());db.sql("insert into ouf_udp.materialization_job(job_id,handoff_id,api_tenant_id) values(gen_random_uuid(),:h,:tenant)").param("h",id).param("tenant",api?tenantId:null,Types.VARCHAR).update();if(sourceRaw!=null){if(sourceRaw.segment()!=null)retainRaw(sourceRaw.segment().pack());db.sql("update ouf_udp.handoff_intake set source_raw_lake_object_id=:raw where handoff_id=:h").param("raw",sourceRaw.id()).param("h",id).update();retainRaw(sourceRaw.id());}event(id,"HANDOFF_DURABLE",finalRawLakeObject==null?Map.of("receiptRef",receipt):Map.of("receiptRef",receipt,"rawLakeObjectId",finalRawLakeObject.toString()));return new Receipt(receipt,true,false);});
  }
  Map<String,Object> outcomes(String tenant,String source,String run,long after,int limit){
    var rows=db.sql("""
        select position,handoff_id as "handoffId",outcome,source_object_id as "sourceObjectId",
          revision::text as "sourceRevision",urban_object_id as "urbanObjectId"
        from ouf_udp.api_handoff_outcome where tenant_id=:tenant and source_id=:s and ingestion_run_id=:run
          and position>:after order by position limit :lim
        """).param("tenant",tenant).param("s",source).param("run",run).param("after",after).param("lim",limit+1).query().listOfRows();
    boolean more=rows.size()>limit;
    var items=more?rows.subList(0,limit):rows;
    return Map.of("schema","ouf.udp-handoff-outcomes.v1","items",items,"hasMore",more,
        "nextAfter",items.isEmpty()?after:items.getLast().get("position"));
  }
  public Map<String,Object> handoff(String id){
    var row=db.sql("""
        select h.handoff_id,h.ingestion_run_id,h.ingestion_id,h.source_id,h.type_code,h.source_object_id,
          h.content_hash,h.receipt_ref,h.raw_lake_object_id,h.state,h.received_at,h.processed_at,h.failure_code,
          (select outcome from ouf_udp.api_handoff_outcome a where a.handoff_id=h.handoff_id) as outcome,
          j.safe_failure_code job_code,
        """+SemanticReferenceFailure.RETAINED_DETAIL_SQL+"""
          reference_failure from ouf_udp.handoff_intake h left join ouf_udp.materialization_job j using(handoff_id)
          where h.handoff_id=:id and (j.api_semantics is not true or coalesce(j.api_tenant_id,:tenant)=:tenant)
        """).param("id",id).param("tenant",tenantId).query().listOfRows().stream().findFirst()
        .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"UDP_HANDOFF_NOT_FOUND"));
    var view=new LinkedHashMap<>(row);
    if(view.get("outcome")==null)view.remove("outcome");
    Object reason=view.remove("job_code");
    if(view.remove("reference_failure") instanceof String detail){
      view.put("failure_code",reason);
      try{view.put("referenceFailure",json.readTree(detail));}
      catch(java.io.IOException malformed){throw new IllegalStateException("UDP_STORED_JSON_INVALID");}
    }
    return view;
  }
  private SourceRaw verifySourceRaw(Map<String,Object> payload,Map<String,Object> identity){
    String ref=text(payload,"rawObjectRef");if(!ref.startsWith("lake://"))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"UDP_RAW_REFERENCE_REQUIRED");
    UUID id;try{id=UUID.fromString(ref.substring(7));}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"UDP_RAW_REFERENCE_INVALID");}
    var raw=lakeCatalog.get(id);
    if(!tenantId.equals(raw.tenant())||!text(identity,"sourceId").equals(raw.source())||!text(identity,"typeCode").equals(raw.type())||!"RAW".equals(raw.tier())||!Set.of("VERIFIED","COMPACTED").contains(raw.state()))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"UDP_RAW_REFERENCE_NOT_VERIFIED");
    return new SourceRaw(id,raw.state(),raw.locator(),"COMPACTED".equals(raw.state())?lake.verifyCompacted(raw):null);
  }
  private record SourceRaw(UUID id,String state,String locator,LakeCatalogRepository.Segment segment){}
  private void lockSourceRaw(SourceRaw raw){
    lockLocator(raw.id(),raw.state(),raw.locator());
    if(raw.segment()!=null)lockLocator(raw.segment().pack(),raw.segment().state(),raw.segment().catalogLocator());
  }
  private void lockLocator(UUID id,String state,String locator){
    if(db.sql("select 1 from ouf_udp.lake_object where lake_object_id=:id and state=:state and locator=:locator for update")
        .param("id",id).param("state",state).param("locator",locator).query(Integer.class).optional().isEmpty())
      throw new IllegalStateException("UDP_RAW_REFERENCE_NOT_VERIFIED");
  }
  private void retainRaw(UUID id){
    db.sql("update ouf_udp.lake_object set blocking_lineage_refs=blocking_lineage_refs+1,state_version=state_version+1 where lake_object_id=:id")
        .param("id",id).update();
  }
  private Receipt duplicate(String hash,Map<String,Object> existing){if(!hash.equals(existing.get("content_hash"))||!Boolean.TRUE.equals(existing.get("payload_matches")))throw new ResponseStatusException(HttpStatus.CONFLICT,"UDP_HANDOFF_IDEMPOTENCY_CONFLICT");return new Receipt(String.valueOf(existing.get("receipt_ref")),true,true);}
  private void event(String id,String type,Map<String,Object> detail){db.sql("insert into ouf_udp.handoff_event(event_id,handoff_id,event_type,safe_detail) values(gen_random_uuid(),:h,:t,cast(:d as jsonb))").param("h",id).param("t",type).param("d",write(detail)).update();}
  private String write(Object v){try{return json.writeValueAsString(v);}catch(JsonProcessingException e){throw new IllegalArgumentException("UDP_JSON_INVALID",e);}}
  @SuppressWarnings("unchecked") static Map<String,Object> object(Map<String,Object> map,String key){return (Map<String,Object>)map.get(key);}static String text(Map<String,Object> map,String key){return String.valueOf(map.get(key));}
  public record Receipt(String receiptRef,boolean durable,boolean duplicate){}
}
