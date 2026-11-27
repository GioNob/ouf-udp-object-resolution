package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Prepared atomic path. Publication and semantic-assertion gates remain separate. */
@Service
public class GovernedIdentityResolutionService {
  private final JdbcClient db;
  private final ObjectMapper json;
  private final GovernedIdentityScopeLock locks;
  private final ScopedIdentityCandidateRepository candidates;
  private final GovernedIdentitySubjectMapper subjects=new GovernedIdentitySubjectMapper();
  private final GovernedIdentityReviewRepository reviews;
  private final GovernedIdentityEngine engine=new GovernedIdentityEngine();
  public GovernedIdentityResolutionService(JdbcClient db,ObjectMapper json,GovernedIdentityScopeLock locks,
      ScopedIdentityCandidateRepository candidates,GovernedIdentityReviewRepository reviews){
    this.db=db;this.json=json;this.locks=locks;this.candidates=candidates;this.reviews=reviews;
  }

  @Transactional public Result resolve(String handoffId,Map<String,Object> handoff,
      UdpPorts.MaterializationProfile materialization,GovernedIdentityEngine.Policy policy){
    Objects.requireNonNull(policy);
    locks.acquire(policy.tenantId(),policy.canonicalClass());
    var subject=subjects.map(handoffId,handoff,materialization,policy);
    Map<String,Object> source=HandoffIntakeService.object(handoff,"sourceIdentity");
    String type=HandoffIntakeService.text(source,"typeCode");
    String sourceObject=HandoffIntakeService.text(source,"sourceObjectId");
    Map<String,Object> stored=db.sql("select source_id,type_code,source_object_id from ouf_udp.handoff_intake where handoff_id=:h for update")
        .param("h",handoffId).query().singleRow();
    if(!policy.sourceId().equals(stored.get("source_id"))||!type.equals(stored.get("type_code"))
        ||!sourceObject.equals(stored.get("source_object_id")))throw invalid();
    GovernedIdentityScopeLock.lockBinding(db,policy.sourceId(),type,sourceObject);
    List<Map<String,Object>> binding=db.sql("select b.urban_object_id,b.first_handoff_id,b.state,o.tenant_id,o.canonical_type,o.status object_status from ouf_udp.source_binding b join ouf_udp.urban_object o on o.urban_object_id=b.urban_object_id where b.source_id=:s and b.type_code=:t and b.source_object_id=:o for update of b")
        .param("s",policy.sourceId()).param("t",type).param("o",sourceObject).query().listOfRows();
    UUID bound=binding.isEmpty()?null:(UUID)binding.getFirst().get("urban_object_id");
    boolean healthy=bound!=null&&"ACTIVE".equals(binding.getFirst().get("state"))
        &&"ACTIVE".equals(binding.getFirst().get("object_status"))
        &&policy.tenantId().equals(binding.getFirst().get("tenant_id"))
        &&policy.canonicalClass().equals(binding.getFirst().get("canonical_type"));
    List<Map<String,Object>> prior=db.sql("select d.outcome,d.target_urban_object_id,d.strategy_id,d.policy_ref,d.strategy_version,i.state issue_state,i.reevaluation_pending,i.version issue_version,i.tenant_id issue_tenant,i.issue_id,i.evidence_refs::text issue_evidence,hd.action human_action,hd.target_urban_object_id human_target from ouf_udp.resolution_decision d left join ouf_udp.resolution_issue i on i.resolution_decision_id=d.resolution_decision_id left join lateral (select action,target_urban_object_id from ouf_udp.human_resolution_decision where issue_id=i.issue_id order by decided_at desc,decision_id desc limit 1) hd on true where d.handoff_id=:h")
        .param("h",handoffId).query().listOfRows();
    if(!prior.isEmpty()){
      var old=prior.getFirst();
      if(!"GOVERNED_IDENTITY".equals(old.get("strategy_id"))
          ||!policy.ref().equals(old.get("policy_ref"))||!policy.version().equals(old.get("strategy_version")))
        throw new IllegalStateException("UDP_IDENTITY_DECISION_CONFLICT");
      String outcome=String.valueOf(old.get("outcome"));
      if("REVIEW_REQUIRED".equals(outcome)){
        if("OPEN".equals(old.get("issue_state"))&&Boolean.TRUE.equals(old.get("reevaluation_pending"))&&policy.tenantId().equals(old.get("issue_tenant"))){
          refreshReview(old,policy,subject,candidates.retrieve(policy,subject),healthy?bound:null,!binding.isEmpty());
          return new Result("REVIEW_REQUIRED",null,false);
        }
        if(!"RESOLVED".equals(old.get("issue_state")))
          return new Result("REVIEW_REQUIRED",null,true);
        if("CREATE_NEW".equals(old.get("human_action"))&&!healthy){
          var coverage=candidates.retrieve(policy,subject);
          if(!binding.isEmpty()){
            refreshReview(old,policy,subject,coverage,null,true);
            return new Result("REVIEW_REQUIRED",null,false);
          }
          if(!coverage.complete()||!sameReviewedCoverage(String.valueOf(old.get("issue_evidence")),coverage.coverageRef(),engine.decide(policy,subject,coverage))){
            refreshReview(old,policy,subject,coverage,null,false);
            return new Result("REVIEW_REQUIRED",null,false);
          }
          UUID created=UUID.randomUUID();
          db.sql("insert into ouf_udp.urban_object(urban_object_id,tenant_id,canonical_type) values(:id,:tenant,:type)")
              .param("id",created).param("tenant",policy.tenantId()).param("type",policy.canonicalClass()).update();
          int inserted=db.sql("insert into ouf_udp.source_binding(source_id,type_code,source_object_id,urban_object_id,first_handoff_id) values(:s,:t,:o,:u,:h) on conflict do nothing")
              .param("s",policy.sourceId()).param("t",type).param("o",sourceObject)
              .param("u",created).param("h",handoffId).update();
          if(inserted!=1)throw new IllegalStateException("UDP_IDENTITY_BINDING_RACE");
          return new Result("NEW_OBJECT",created,false,coverage.coverageRef());
        }
        boolean created="CREATE_NEW".equals(old.get("human_action"));
        if(!healthy||(created?!handoffId.equals(binding.getFirst().get("first_handoff_id")):!bound.equals(old.get("human_target")))){
          refreshReview(old,policy,subject,candidates.retrieve(policy,subject),healthy?bound:null,!binding.isEmpty());
          return new Result("REVIEW_REQUIRED",null,false);
        }
        return resume(created?"NEW_OBJECT":"MATCH",handoffId,bound,policy,subject);
      }
      if(!healthy||("MATCH".equals(outcome)?!bound.equals(old.get("target_urban_object_id")):
          !handoffId.equals(binding.getFirst().get("first_handoff_id"))))
        throw new IllegalStateException("UDP_IDENTITY_BINDING_MISSING");
      return resume(outcome,handoffId,bound,policy,subject);
    }
    // An ACTIVE source binding is continuity evidence in its own right. A changed
    // observation may change properties without changing the canonical identity.
    if(healthy){
      var coverage=candidates.retrieve(policy,subject);
      String bindingRef="source-binding://"+policy.sourceId()+"/"+type+"/"+sourceObject;
      Map<String,Object> evidence=new LinkedHashMap<>();
      evidence.put("bindingRef",bindingRef);
      evidence.put("targetUrbanObjectId",bound);
      evidence.put("policyRef",policy.ref());
      evidence.put("policyVersion",policy.version());
      evidence.put("reason","ACTIVE_SOURCE_BINDING");
      db.sql("insert into ouf_udp.resolution_decision(resolution_decision_id,handoff_id,candidate_ref,outcome,target_urban_object_id,strategy_id,strategy_version,evidence_refs,decided_by,policy_ref) values(gen_random_uuid(),:h,:c,'MATCH',:u,'GOVERNED_IDENTITY',:v,cast(:e as jsonb),'SERVICE_IDENTITY',:p)")
          .param("h",handoffId).param("c",bindingRef).param("u",bound)
          .param("v",policy.version()).param("e",write(List.of(evidence))).param("p",policy.ref()).update();
      return new Result("MATCH",bound,false,coverage.complete()?coverage.coverageRef():null);
    }
    if(!binding.isEmpty()){
      var conflict=new GovernedIdentityEngine.Decision(GovernedIdentityEngine.Outcome.REVIEW_REQUIRED,
          null,"SOURCE_BINDING_CONFLICT",policy.ref(),policy.version(),List.of());
      var unavailable=new GovernedIdentityEngine.Candidates(policy.ref(),policy.version(),
          policy.tenantId(),policy.canonicalClass(),null,false,List.of());
      reviews.record(handoffId,conflict,unavailable,false);
      return new Result("REVIEW_REQUIRED",null,false);
    }
    var retrieved=candidates.retrieve(policy,subject);
    var decision=engine.decide(policy,subject,retrieved);
    if(decision.outcome()==GovernedIdentityEngine.Outcome.REVIEW_REQUIRED
        ||decision.outcome()==GovernedIdentityEngine.Outcome.RESOLUTION_TOO_BROAD){
      reviews.record(handoffId,decision,retrieved,binding.isEmpty());
      return new Result("REVIEW_REQUIRED",null,false);
    }
    if(!binding.isEmpty()&&(!healthy||decision.outcome()!=GovernedIdentityEngine.Outcome.MATCH
        ||!bound.equals(decision.objectId()))){
      var conflict=new GovernedIdentityEngine.Decision(GovernedIdentityEngine.Outcome.REVIEW_REQUIRED,
          null,"SOURCE_BINDING_CONFLICT",policy.ref(),policy.version(),decision.assessments());
      reviews.record(handoffId,conflict,retrieved,false);
      return new Result("REVIEW_REQUIRED",null,false);
    }
    UUID target=decision.outcome()==GovernedIdentityEngine.Outcome.MATCH?decision.objectId():UUID.randomUUID();
    if(decision.outcome()==GovernedIdentityEngine.Outcome.NEW_OBJECT){
      db.sql("insert into ouf_udp.urban_object(urban_object_id,tenant_id,canonical_type) values(:id,:tenant,:type)")
          .param("id",target).param("tenant",policy.tenantId()).param("type",policy.canonicalClass()).update();
    }
    Map<String,Object> evidence=new LinkedHashMap<>();
    evidence.put("policyRef",policy.ref());evidence.put("policyVersion",policy.version());
    evidence.put("tenantId",policy.tenantId());evidence.put("canonicalClass",policy.canonicalClass());
    evidence.put("coverageRef",retrieved.coverageRef());evidence.put("complete",retrieved.complete());
    evidence.put("outcome",decision.outcome().name());evidence.put("reason",decision.reason());
    evidence.put("assessments",decision.assessments());
    db.sql("insert into ouf_udp.resolution_decision(resolution_decision_id,handoff_id,candidate_ref,outcome,target_urban_object_id,strategy_id,strategy_version,evidence_refs,decided_by,policy_ref) values(gen_random_uuid(),:h,:c,:o,:u,'GOVERNED_IDENTITY',:v,cast(:e as jsonb),'SERVICE_IDENTITY',:p)")
        .param("h",handoffId).param("c",retrieved.coverageRef())
        .param("o",decision.outcome().name())
        .param("u",decision.outcome()==GovernedIdentityEngine.Outcome.MATCH?target:null,java.sql.Types.OTHER)
        .param("v",policy.version()).param("e",write(List.of(evidence))).param("p",policy.ref()).update();
    if(bound==null){
      int inserted=db.sql("insert into ouf_udp.source_binding(source_id,type_code,source_object_id,urban_object_id,first_handoff_id) values(:s,:t,:o,:u,:h) on conflict do nothing")
          .param("s",policy.sourceId()).param("t",type).param("o",sourceObject).param("u",target)
          .param("h",handoffId).update();
      if(inserted!=1)throw new IllegalStateException("UDP_IDENTITY_BINDING_RACE");
    }
    return new Result(decision.outcome().name(),target,false,retrieved.coverageRef());
  }
  private Result resume(String outcome,String handoff,UUID bound,GovernedIdentityEngine.Policy policy,
      GovernedIdentityEngine.Subject subject){
    var observed=db.sql("select urban_object_id from ouf_udp.materialization_observation where handoff_id=:h")
        .param("h",handoff).query(UUID.class).optional();
    if(observed.isPresent()){
      if(!bound.equals(observed.get()))throw new IllegalStateException("UDP_IDENTITY_OBSERVATION_CONFLICT");
      return new Result(outcome,bound,true);
    }
    var coverage=candidates.retrieve(policy,subject);
    return new Result(outcome,bound,false,coverage.complete()?coverage.coverageRef():null,true);
  }
  private String write(Object value){try{return json.writeValueAsString(value);}
    catch(JsonProcessingException failure){throw new IllegalStateException("UDP_IDENTITY_EVIDENCE_INVALID",failure);}}
  private boolean sameReviewedCoverage(String encoded,String current,GovernedIdentityEngine.Decision currentDecision){
    try{
      var evidence=json.readTree(encoded);
      if(!evidence.isArray()||evidence.isEmpty()||!evidence.get(0).path("complete").asBoolean(false))return false;
      if(!evidence.get(0).path("assessments").isArray())return false;
      var reviewed=json.convertValue(evidence.get(0).path("assessments"),
          new com.fasterxml.jackson.core.type.TypeReference<List<GovernedIdentityEngine.Assessment>>(){});
      if(!currentDecision.assessments().equals(reviewed))return false;
      String observed=evidence.get(0).path("coverageRef").asText();
      String prefix="indexed-snapshot://";
      if(current==null||!current.startsWith(prefix)||!observed.startsWith(prefix))return false;
      int snapshotSeparator=current.lastIndexOf('/');
      int transactionSeparator=current.lastIndexOf('/',snapshotSeparator-1);
      if(transactionSeparator<=prefix.length())return false;
      String currentRef=current.substring(prefix.length(),transactionSeparator);
      return observed.startsWith(prefix+currentRef+"/");
    }catch(com.fasterxml.jackson.core.JsonProcessingException|IllegalArgumentException invalid){return false;}
  }
  private void refreshReview(Map<String,Object> old,GovernedIdentityEngine.Policy policy,
      GovernedIdentityEngine.Subject subject,GovernedIdentityEngine.Candidates coverage,UUID activeBinding,boolean bindingPresent){
    var decision=engine.decide(policy,subject,coverage);
    List<UUID> selectable=coverage.complete()
        ?coverage.rows().stream().map(GovernedIdentityEngine.Candidate::objectId)
            .filter(id->decision.assessments().stream().noneMatch(a->a.objectId().equals(id)&&a.excluded()))
            .toList():List.of();
    if(bindingPresent)selectable=activeBinding==null||!coverage.complete()?List.of():List.of(activeBinding);
    String reason="RESOLVED".equals(old.get("issue_state"))?"NEW_IDENTITY_EVIDENCE_STALE":
        bindingPresent?(activeBinding==null?"SOURCE_BINDING_CONFLICT":"ACTIVE_SOURCE_BINDING"):decision.reason();
    Map<String,Object> evidence=new LinkedHashMap<>();
    evidence.put("activeBindingTarget",activeBinding);
    evidence.put("policyRef",policy.ref());evidence.put("policyVersion",policy.version());
    evidence.put("tenantId",policy.tenantId());evidence.put("canonicalClass",policy.canonicalClass());
    evidence.put("coverageRef",coverage.coverageRef());evidence.put("complete",coverage.complete());
    evidence.put("outcome",decision.outcome().name());evidence.put("reason",reason);
    evidence.put("assessments",decision.assessments());
    int changed=db.sql("update ouf_udp.resolution_issue set state='OPEN',version=version+1,reevaluation_pending=false,reason_code=:reason,candidate_refs=cast(:c as jsonb),evidence_refs=cast(:e as jsonb),resolved_at=null,resolved_by_subject=null,resolution_reason=null,authorization_decision_ref=null where issue_id=:i and state=:state and version=:v and tenant_id=:tenant")
        .param("i",old.get("issue_id")).param("state",old.get("issue_state")).param("v",old.get("issue_version"))
        .param("tenant",policy.tenantId()).param("reason",reason).param("c",write(selectable)).param("e",write(List.of(evidence))).update();
    if(changed!=1)throw new IllegalStateException("UDP_ISSUE_VERSION_CONFLICT");
  }
  private static IllegalArgumentException invalid(){return new IllegalArgumentException("UDP_IDENTITY_HANDOFF_MISMATCH");}
  public record Result(String outcome,UUID targetUrbanObjectId,boolean duplicate,String coverageRef,boolean resumed){
    public Result(String outcome,UUID target,boolean duplicate,String coverage){this(outcome,target,duplicate,coverage,false);}
    public Result(String outcome,UUID targetUrbanObjectId,boolean duplicate){this(outcome,targetUrbanObjectId,duplicate,null);}
  }
}
