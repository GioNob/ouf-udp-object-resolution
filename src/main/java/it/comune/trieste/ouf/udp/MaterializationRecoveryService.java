package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import it.comune.trieste.ouf.authorization.ResourceContext;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Governed retry of the original durable job, before any canonical decision. */
@Service
public class MaterializationRecoveryService {
  public static final String CAPABILITY="udp.materialization.retry";
  private static final Set<String> TECHNICAL_CODES=Set.of(
      "UDP_REFERENCE_INTEGRITY_CONTRACT_INVALID","UDP_REFERENCE_INTEGRITY_CATALOG_INVALID",
      "UDP_REFERENCE_INTEGRITY_MISSING");
  private final JdbcClient db;
  private final ObjectMapper json;
  private final HistoricalContractCatalog catalog;
  public MaterializationRecoveryService(JdbcClient db,ObjectMapper json,HistoricalContractCatalog catalog){
    this.db=db;this.json=json;this.catalog=catalog;
  }

  @Transactional(readOnly=true)
  public Review review(UUID jobId,TrustedHumanContext actor,Consumer<ResourceContext> authorize){
    actor.require(CAPABILITY);
    Map<String,Object> row=load(jobId,actor.tenantId(),false);
    authorize.accept(resource(row,actor.tenantId()));
    return review(row);
  }

  @Transactional
  public Receipt retry(UUID jobId,Request request,TrustedHumanContext actor,
      Consumer<ResourceContext> authorize){
    actor.require(CAPABILITY);
    if(request==null||request.operationId()==null||request.expectedVersion()==null
        ||request.expectedVersion()<0||request.expectedSnapshotHash()==null
        ||!request.expectedSnapshotHash().matches("sha256:[a-f0-9]{64}")
        ||request.reason()==null||request.reason().isBlank()||request.reason().length()>2000
        ||request.reason().chars().anyMatch(Character::isISOControl))throw badRequest();
    // Serialize same operation IDs across jobs; the append-only event PK is the durable key.
    db.sql("select pg_advisory_xact_lock(hashtextextended(:operation,0))")
        .param("operation",request.operationId().toString()).query().singleRow();
    Map<String,Object> row=load(jobId,actor.tenantId(),true);
    authorize.accept(resource(row,actor.tenantId()));
    String requestHash=digest(Map.of("job",jobId.toString(),"tenant",actor.tenantId(),
        "subject",actor.subject(),"expectedVersion",request.expectedVersion(),
        "snapshotHash",request.expectedSnapshotHash(),"reason",request.reason()));
    List<Map<String,Object>> previous=db.sql(
        "select handoff_id,event_type,safe_detail::text detail from ouf_udp.handoff_event where event_id=:id")
        .param("id",request.operationId()).query().listOfRows();
    if(!previous.isEmpty()){
      Map<String,Object> old=previous.getFirst();Map<String,Object> detail=read(String.valueOf(old.get("detail")));
      if(!"MATERIALIZATION_RETRY_AUTHORIZED".equals(old.get("event_type"))
          ||!row.get("handoff_id").equals(old.get("handoff_id"))
          ||!requestHash.equals(detail.get("requestHash")))throw conflict("UDP_MATERIALIZATION_RETRY_OPERATION_CONFLICT");
      return new Receipt(request.operationId(),jobId,String.valueOf(row.get("handoff_id")),
          ((Number)detail.get("acceptedVersion")).longValue(),String.valueOf(row.get("state")),true);
    }
    if(version(row)!=request.expectedVersion())throw conflict("UDP_MATERIALIZATION_RETRY_STALE");
    Review current=review(row);
    if(!current.retryEligible())throw conflict("UDP_MATERIALIZATION_RETRY_INELIGIBLE");
    if(!current.contractReady())throw conflict("UDP_MATERIALIZATION_RETRY_REFERENCES_UNAVAILABLE");
    if(!current.snapshotHash().equals(request.expectedSnapshotHash()))
      throw conflict("UDP_MATERIALIZATION_RETRY_STALE");
    Object baseline=row.get("integrity_baseline_hash");
    if(baseline!=null&&!baseline.equals(current.verifiedBaselineHash()))
      throw conflict("UDP_MATERIALIZATION_RETRY_BASELINE_DRIFT");
    int changed=db.sql("update ouf_udp.materialization_job set state='READY',state_version=state_version+1,"
        +"next_integrity_check_at=null,updated_at=transaction_timestamp() "
        +"where job_id=:id and state='QUARANTINED' and state_version=:version "
        +"and claimed_by is null and lease_until is null")
        .param("id",jobId).param("version",request.expectedVersion()).update();
    if(changed!=1)throw conflict("UDP_MATERIALIZATION_RETRY_STALE");
    long accepted=version(row)+1;
    Map<String,Object> detail=new LinkedHashMap<>();
    detail.put("jobId",jobId.toString());detail.put("operationId",request.operationId().toString());
    detail.put("fromVersion",version(row));detail.put("acceptedVersion",accepted);
    detail.put("previousSafeFailureCode",row.get("safe_failure_code"));
    detail.put("actorType",actor.actorType());detail.put("actorSubject",actor.subject());
    detail.put("tenantId",actor.tenantId());detail.put("authorizationDecisionRef",actor.authorizationDecisionRef());
    detail.put("correlationId",actor.correlationId());detail.put("reason",request.reason());
    detail.put("snapshotHash",current.snapshotHash());detail.put("verifiedBaselineHash",current.verifiedBaselineHash());
    detail.put("requestHash",requestHash);
    db.sql("insert into ouf_udp.handoff_event(event_id,handoff_id,event_type,safe_detail) "
        +"values(:id,:handoff,'MATERIALIZATION_RETRY_AUTHORIZED',cast(:detail as jsonb))")
        .param("id",request.operationId()).param("handoff",row.get("handoff_id"))
        .param("detail",write(detail)).update();
    return new Receipt(request.operationId(),jobId,String.valueOf(row.get("handoff_id")),accepted,"READY",false);
  }

  private Map<String,Object> load(UUID id,String tenant,boolean lock){
    if(id==null||tenant==null||tenant.isBlank())throw new SecurityException("UDP_MATERIALIZATION_RECOVERY_TENANT_REQUIRED");
    List<Map<String,Object>> rows=db.sql("select j.job_id,j.handoff_id,j.state,j.state_version,j.attempts,"
        +"j.integrity_attempts,j.integrity_baseline_hash,j.safe_failure_code,j.claimed_by,j.lease_until,"
        +"h.state intake_state,h.ingestion_run_id,h.source_id,h.type_code,h.content_hash,h.payload_json::text payload,"
        +"l.lake_object_id,l.state lake_state,l.state_version lake_version,l.access_label,"
        +"(exists(select 1 from ouf_udp.resolution_decision d where d.handoff_id=h.handoff_id) "
        +"or exists(select 1 from ouf_udp.materialization_observation o where o.handoff_id=h.handoff_id) "
        +"or exists(select 1 from ouf_udp.object_revision r where r.source_handoff_id=h.handoff_id) "
        +"or exists(select 1 from ouf_udp.property_contribution p where p.handoff_id=h.handoff_id) "
        +"or exists(select 1 from ouf_udp.source_binding b where b.first_handoff_id=h.handoff_id)) decision_present "
        +"from ouf_udp.materialization_job j join ouf_udp.handoff_intake h on h.handoff_id=j.handoff_id "
        +"join ouf_udp.lake_object l on l.lake_object_id=h.raw_lake_object_id "
        +"where j.job_id=:id and l.tenant_id=:tenant and l.tier='RAW' "
        +"and l.source_id=h.source_id and l.type_code=h.type_code"+(lock?" for update of j,h,l":""))
        .param("id",id).param("tenant",tenant).query().listOfRows();
    if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"UDP_MATERIALIZATION_JOB_NOT_FOUND");
    return rows.getFirst();
  }

  private Review review(Map<String,Object> row){
    boolean eligible="QUARANTINED".equals(row.get("state"))&&"DURABLE".equals(row.get("intake_state"))
        &&TECHNICAL_CODES.contains(Objects.toString(row.get("safe_failure_code"),""))
        &&row.get("claimed_by")==null&&row.get("lease_until")==null
        &&!Boolean.TRUE.equals(row.get("decision_present"))
        &&Set.of("VERIFIED","COMPACTED","COLD").contains(row.get("lake_state"));
    String baseline=null;String check="NOT_ELIGIBLE";
    if(eligible){
      try{
        var result=catalog.resolve(HandoffIntakeService.object(read(String.valueOf(row.get("payload"))),"contractRefs"));
        if(result.ready()&&result.baselineHash()!=null){baseline=result.baselineHash();check="READY";}
        else check="MISSING";
      }catch(IllegalArgumentException invalid){check="CONTRACT_INVALID";}
      catch(IllegalStateException unavailable){check="CATALOG_UNAVAILABLE";}
    }
    Map<String,Object> snapshot=new TreeMap<>();
    for(String key:List.of("job_id","handoff_id","state","state_version","attempts","integrity_attempts",
        "integrity_baseline_hash","safe_failure_code","intake_state","ingestion_run_id","source_id",
        "type_code","content_hash","payload","lake_object_id","lake_state","lake_version","access_label",
        "decision_present"))snapshot.put(key,row.get(key));
    snapshot.put("verifiedBaselineHash",baseline);snapshot.put("contractCheck",check);
    return new Review((UUID)row.get("job_id"),String.valueOf(row.get("handoff_id")),
        String.valueOf(row.get("ingestion_run_id")),String.valueOf(row.get("source_id")),
        String.valueOf(row.get("state")),version(row),String.valueOf(row.get("intake_state")),
        Objects.toString(row.get("safe_failure_code"),null),eligible,"READY".equals(check),check,
        baseline,digest(snapshot),((Number)row.get("attempts")).intValue(),
        ((Number)row.get("integrity_attempts")).intValue());
  }
  private static ResourceContext resource(Map<String,Object> row,String tenant){
    return new ResourceContext("materialization-job",String.valueOf(row.get("job_id")),tenant,
        null,Map.of("module","UDP","sourceRef",String.valueOf(row.get("source_id")),
        "jobRef",String.valueOf(row.get("ingestion_run_id")),"typeRef",String.valueOf(row.get("type_code")),
        "dataAccessLabel",String.valueOf(row.get("access_label"))));
  }
  private static long version(Map<String,Object> row){return ((Number)row.get("state_version")).longValue();}
  private Map<String,Object> read(String value){try{return json.readValue(value,new TypeReference<>(){});}
    catch(Exception invalid){throw new IllegalStateException("UDP_MATERIALIZATION_RECOVERY_JSON_INVALID");}}
  private String write(Object value){try{return json.writeValueAsString(value);}
    catch(Exception invalid){throw new IllegalStateException("UDP_MATERIALIZATION_RECOVERY_JSON_INVALID");}}
  private String digest(Object value){try{return HistoricalContractCatalog.hash(
      json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).writeValueAsString(value));}
    catch(Exception invalid){throw new IllegalStateException("UDP_MATERIALIZATION_RECOVERY_HASH_INVALID");}}
  private static ResponseStatusException conflict(String code){return new ResponseStatusException(HttpStatus.CONFLICT,code);}
  private static ResponseStatusException badRequest(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"UDP_MATERIALIZATION_RETRY_REQUEST_INVALID");}
  public record Request(UUID operationId,Long expectedVersion,String expectedSnapshotHash,String reason){}
  public record Review(UUID jobId,String handoffId,String ingestionRunId,String sourceId,String state,long stateVersion,
      String intakeState,String safeFailureCode,boolean retryEligible,boolean contractReady,String contractCheck,
      String verifiedBaselineHash,String snapshotHash,int attempts,int integrityAttempts){}
  public record Receipt(UUID operationId,UUID jobId,String handoffId,long acceptedVersion,String state,boolean repeated){}
}
