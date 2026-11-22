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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Governed retry of the original durable job, without canonical effects, preserving approved CREATE_NEW decisions. */
@Service
public class MaterializationRecoveryService {
  public static final String CAPABILITY="udp.materialization.retry";
  private static final Set<String> TECHNICAL_CODES=Set.of(
      "UDP_REFERENCE_INTEGRITY_CONTRACT_INVALID","UDP_REFERENCE_INTEGRITY_CATALOG_INVALID",
      "UDP_REFERENCE_INTEGRITY_MISSING","UDP_SEMANTIC_REFERENCE_MISMATCH","UDP_SEMANTIC_REFERENCE_UNRESOLVABLE",
      "UDP_SEMANTIC_REFERENCE_TOO_LARGE");
  private final JdbcClient db;
  private final TransactionTemplate transactions;
  private final ObjectMapper json;
  private final HistoricalContractCatalog catalog;
  @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<PublishedRuntimeConfiguration> publications;
  public MaterializationRecoveryService(JdbcClient db,ObjectMapper json,HistoricalContractCatalog catalog,TransactionTemplate transactions){
    this.transactions=transactions;
    this.db=db;this.json=json;this.catalog=catalog;
  }

  public Review review(UUID jobId,TrustedHumanContext actor,Consumer<ResourceContext> authorize){
    actor.require(CAPABILITY);
    Map<String,Object> row=load(jobId,actor.tenantId(),false);
    authorize.accept(resource(row,actor.tenantId()));
    return review(row);
  }

  public Receipt retry(UUID jobId,Request request,TrustedHumanContext actor,
      Consumer<ResourceContext> authorize){
    actor.require(CAPABILITY);
    if(request==null||request.operationId()==null||request.expectedVersion()==null
        ||request.expectedVersion()<0||request.expectedSnapshotHash()==null
        ||!request.expectedSnapshotHash().matches("sha256:[a-f0-9]{64}")
        ||request.reason()==null||request.reason().isBlank()||request.reason().length()>2000
        ||request.reason().chars().anyMatch(Character::isISOControl))throw badRequest();
    var observed=load(jobId,actor.tenantId(),false);
    authorize.accept(resource(observed,actor.tenantId()));
    // A previously accepted operation may be replayed after its job has moved on.
    Review checked=version(observed)==request.expectedVersion() ? review(observed) : null;
    return transactions.execute(status->acceptRetry(jobId,request,actor,authorize,observed,checked));
  }

  private Receipt acceptRetry(UUID jobId,Request request,TrustedHumanContext actor,
      Consumer<ResourceContext> authorize,Map<String,Object> observed,Review current){
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
    // Revalidate the entire local input under the original locks after paced external reads.
    if(current==null||!row.equals(observed))throw conflict("UDP_MATERIALIZATION_RETRY_STALE");
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
    detail.put("preservesApprovedNewIdentity",approvedNewIdentity(row));
    detail.put("identityReviewSnapshotHash",digest(row.get("identity_review_snapshot")));
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
        +"h.state intake_state,h.payload_json#>>'{contractRefs,bundleRef}' bundle_ref,h.ingestion_run_id,h.source_id,h.type_code,h.source_object_id,h.content_hash,h.payload_json::text payload,"
        +"l.lake_object_id,l.state lake_state,l.state_version lake_version,l.access_label,"
        +"l.tenant_id,"
        +SemanticReferenceFailure.RETAINED_DETAIL_SQL+" reference_failure,"
        +"(exists(select 1 from ouf_udp.resolution_decision d where d.handoff_id=h.handoff_id)) decision_present,"
        +"(exists(select 1 from ouf_udp.materialization_observation o where o.handoff_id=h.handoff_id) "
        +"or exists(select 1 from ouf_udp.object_revision r where r.source_handoff_id=h.handoff_id) "
        +"or exists(select 1 from ouf_udp.property_contribution p where p.handoff_id=h.handoff_id) "
        +"or exists(select 1 from ouf_udp.source_binding b where b.first_handoff_id=h.handoff_id)) canonical_effect_present,"
        +"coalesce((select jsonb_agg(jsonb_build_object('decision',to_jsonb(d),'issue',to_jsonb(i),'human',to_jsonb(hd)) "
        +"order by d.resolution_decision_id,i.issue_id) from ouf_udp.resolution_decision d "
        +"left join ouf_udp.resolution_issue i on i.resolution_decision_id=d.resolution_decision_id and i.handoff_id=h.handoff_id "
        +"left join lateral(select * from ouf_udp.human_resolution_decision hd where hd.issue_id=i.issue_id "
        +"order by hd.decided_at desc,hd.decision_id desc limit 1) hd on true where d.handoff_id=h.handoff_id),'[]'::jsonb)::text identity_review_snapshot "
        +"from ouf_udp.materialization_job j join ouf_udp.handoff_intake h on h.handoff_id=j.handoff_id "
        +"join ouf_udp.lake_object l on l.lake_object_id=h.raw_lake_object_id "
        +"where j.job_id=:id and l.tenant_id=:tenant and l.tier='RAW' "
        +"and l.source_id=h.source_id and l.type_code=h.type_code"+(lock?" for update of j,h,l":""))
        .param("id",id).param("tenant",tenant).query().listOfRows();
    if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"UDP_MATERIALIZATION_JOB_NOT_FOUND");
    var row=new LinkedHashMap<>(rows.getFirst());
    if(lock)GovernedIdentityScopeLock.lockBinding(db,row.get("source_id"),row.get("type_code"),row.get("source_object_id"));
    var binding=db.sql("""
        select b.urban_object_id,b.xmin::text binding_version,o.revision,o.current_revision_id,o.status,b.state,o.tenant_id,o.canonical_type,
               exists(select 1 from ouf_udp.handoff_property_profile p
                      where p.canonical_type=o.canonical_type and p.bundle_ref=:bundle
                      and p.handoff_id=b.first_handoff_id)
               or exists(select 1 from ouf_udp.object_revision r join ouf_udp.resolution_decision d on d.handoff_id=r.source_handoff_id
                         where r.urban_object_id=o.urban_object_id and r.bundle_ref=:bundle and d.handoff_id=b.first_handoff_id
                           and (jsonb_exists(d.evidence_refs,'scope:canonical-type:'||o.canonical_type)
                                or d.evidence_refs->0->>'canonicalClass'=o.canonical_type)) same_profile
        from ouf_udp.source_binding b join ouf_udp.urban_object o using(urban_object_id)
        where b.source_id=:s and b.type_code=:t and b.source_object_id=:o
        """+(lock?" for update of b,o":""))
        .param("bundle",row.get("bundle_ref"),java.sql.Types.VARCHAR)
        .param("s",row.get("source_id")).param("t",row.get("type_code")).param("o",row.get("source_object_id"))
        .query().listOfRows();
    row.put("binding_snapshot",binding);
    return row;
  }

  @SuppressWarnings("unchecked")
  private boolean compatibleBinding(Map<String,Object> row){
    var binding=(List<Map<String,Object>>)row.get("binding_snapshot");
    if(binding.isEmpty())return true;
    var current=binding.getFirst();
    return (!Boolean.TRUE.equals(row.get("decision_present"))||pendingReevaluation(row))&&"ACTIVE".equals(current.get("state"))
        &&current.get("current_revision_id")!=null&&"ACTIVE".equals(current.get("status"))&&row.get("tenant_id").equals(current.get("tenant_id"))&&compatibleClass(row,current);
  }
  private boolean compatibleClass(Map<String,Object> row,Map<String,Object> binding){
    if(Boolean.TRUE.equals(binding.get("same_profile")))return true;
    var published=publications==null?null:publications.getIfAvailable();
    if(published==null)return false;
    if(!(row.get("bundle_ref") instanceof String bundle)||bundle.isBlank())return false;
    return published.resolve(bundle,String.valueOf(row.get("type_code")))
        .canonicalType().equals(binding.get("canonical_type"));
  }

  private Review review(Map<String,Object> row){
    boolean eligible="QUARANTINED".equals(row.get("state"))&&"DURABLE".equals(row.get("intake_state"))
        &&TECHNICAL_CODES.contains(Objects.toString(row.get("safe_failure_code"),""))
        &&row.get("claimed_by")==null&&row.get("lease_until")==null
        &&!Boolean.TRUE.equals(row.get("canonical_effect_present"))
        &&(!Boolean.TRUE.equals(row.get("decision_present"))||approvedNewIdentity(row)||pendingReevaluation(row))
        &&Set.of("VERIFIED","COMPACTED","COLD").contains(row.get("lake_state"))&&compatibleBinding(row);
    String baseline=null;String check="NOT_ELIGIBLE";
    Map<String,Object> referenceFailure=row.get("reference_failure") instanceof String detail ? read(detail) : null;
    if(eligible){
      try{
        var result=catalog.resolve(HandoffIntakeService.object(read(String.valueOf(row.get("payload"))),"contractRefs"));
        if(result.ready()&&result.baselineHash()!=null){baseline=result.baselineHash();check="READY";}
        else check="MISSING";
      }catch(SemanticReferenceFailure rejected){check=rejected.getMessage();referenceFailure=rejected.detail;}
      catch(IllegalArgumentException invalid){check="CONTRACT_INVALID";}
      catch(IllegalStateException unavailable){check="CATALOG_UNAVAILABLE";}
    }
    Map<String,Object> snapshot=new TreeMap<>();
    for(String key:List.of("job_id","handoff_id","state","state_version","attempts","integrity_attempts",
        "integrity_baseline_hash","safe_failure_code","intake_state","ingestion_run_id","source_id",
        "type_code","content_hash","payload","lake_object_id","lake_state","lake_version","access_label",
        "decision_present","canonical_effect_present","identity_review_snapshot","binding_snapshot"))snapshot.put(key,row.get(key));
    snapshot.put("verifiedBaselineHash",baseline);snapshot.put("contractCheck",check);
    return new Review((UUID)row.get("job_id"),String.valueOf(row.get("handoff_id")),
        String.valueOf(row.get("ingestion_run_id")),String.valueOf(row.get("source_id")),
        String.valueOf(row.get("state")),version(row),String.valueOf(row.get("intake_state")),
        Objects.toString(row.get("safe_failure_code"),null),eligible,"READY".equals(check),check,
        baseline,digest(snapshot),((Number)row.get("attempts")).intValue(),
        ((Number)row.get("integrity_attempts")).intValue(),referenceFailure);
  }
  private boolean pendingReevaluation(Map<String,Object> row){
    try{
      var snapshot=json.readTree(String.valueOf(row.get("identity_review_snapshot")));
      if(!snapshot.isArray()||snapshot.size()!=1)return false;
      var entry=snapshot.get(0);var issue=entry.path("issue");
      return "REVIEW_REQUIRED".equals(entry.path("decision").path("outcome").asText())
          &&"OPEN".equals(issue.path("state").asText())&&issue.path("reevaluation_pending").asBoolean(false)
          &&row.get("tenant_id").equals(issue.path("tenant_id").asText())
          &&row.get("handoff_id").equals(issue.path("handoff_id").asText());
    }catch(Exception invalid){return false;}
  }
  /** A confirmed identity review is not a materialized canonical object.
   * Requeue only its original job: no new identity decision, binding or counter reset.
   * The normal governed identity engine must still verify coverage before creation.
   */
  private boolean approvedNewIdentity(Map<String,Object> row){
    if(row.get("integrity_baseline_hash")==null)return false;
    try{
      var snapshot=json.readTree(String.valueOf(row.get("identity_review_snapshot")));
      if(!snapshot.isArray()||snapshot.size()!=1)return false;
      var entry=snapshot.get(0);var decision=entry.path("decision");
      var issue=entry.path("issue");var human=entry.path("human");
      var evidence=issue.path("evidence_refs");
      return "GOVERNED_IDENTITY".equals(decision.path("strategy_id").asText())
          &&"REVIEW_REQUIRED".equals(decision.path("outcome").asText())
          &&decision.path("target_urban_object_id").isNull()
          &&"RESOLVED".equals(issue.path("state").asText())
          &&Objects.equals(row.get("tenant_id"),issue.path("tenant_id").asText())
          &&evidence.isArray()&&!evidence.isEmpty()&&evidence.get(0).path("complete").asBoolean(false)
          &&Objects.equals(row.get("tenant_id"),evidence.get(0).path("tenantId").asText())
          &&"CREATE_NEW".equals(human.path("action").asText())
          &&human.path("target_urban_object_id").isNull()
          &&!human.path("actor_subject").asText().isBlank()
          &&human.path("actor_subject").asText().equals(issue.path("resolved_by_subject").asText())
          &&!human.path("authorization_decision_ref").asText().isBlank()
          &&human.path("authorization_decision_ref").asText().equals(issue.path("authorization_decision_ref").asText())
          &&!human.path("decided_at").asText().isBlank()
          &&human.path("decided_at").equals(issue.path("resolved_at"));
    }catch(Exception invalid){return false;}
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
      String verifiedBaselineHash,String snapshotHash,int attempts,int integrityAttempts,Map<String,Object> referenceFailure){}
  public record Receipt(UUID operationId,UUID jobId,String handoffId,long acceptedVersion,String state,boolean repeated){}
}

