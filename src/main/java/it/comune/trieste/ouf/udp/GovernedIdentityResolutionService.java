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
    List<Map<String,Object>> binding=db.sql("select b.urban_object_id,b.state,o.tenant_id,o.canonical_type,o.status object_status from ouf_udp.source_binding b join ouf_udp.urban_object o on o.urban_object_id=b.urban_object_id where b.source_id=:s and b.type_code=:t and b.source_object_id=:o for update of b")
        .param("s",policy.sourceId()).param("t",type).param("o",sourceObject).query().listOfRows();
    UUID bound=binding.isEmpty()?null:(UUID)binding.getFirst().get("urban_object_id");
    boolean healthy=bound!=null&&"ACTIVE".equals(binding.getFirst().get("state"))
        &&"ACTIVE".equals(binding.getFirst().get("object_status"))
        &&policy.tenantId().equals(binding.getFirst().get("tenant_id"))
        &&policy.canonicalClass().equals(binding.getFirst().get("canonical_type"));
    List<Map<String,Object>> prior=db.sql("select d.outcome,d.strategy_id,d.policy_ref,d.strategy_version,i.state issue_state from ouf_udp.resolution_decision d left join ouf_udp.resolution_issue i on i.resolution_decision_id=d.resolution_decision_id where d.handoff_id=:h")
        .param("h",handoffId).query().listOfRows();
    if(!prior.isEmpty()){
      var old=prior.getFirst();
      if(!"GOVERNED_IDENTITY".equals(old.get("strategy_id"))
          ||!policy.ref().equals(old.get("policy_ref"))||!policy.version().equals(old.get("strategy_version")))
        throw new IllegalStateException("UDP_IDENTITY_DECISION_CONFLICT");
      String outcome=String.valueOf(old.get("outcome"));
      if("REVIEW_REQUIRED".equals(outcome))
        return "RESOLVED".equals(old.get("issue_state"))&&healthy
            ?new Result("MATCH",bound,true):new Result("REVIEW_REQUIRED",null,true);
      if(!healthy)throw new IllegalStateException("UDP_IDENTITY_BINDING_MISSING");
      return new Result(outcome,bound,true);
    }
    // An ACTIVE source binding is continuity evidence in its own right. A changed
    // observation may change properties without changing the canonical identity.
    if(healthy){
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
      return new Result("MATCH",bound,false);
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
    return new Result(decision.outcome().name(),target,false);
  }
  private String write(Object value){try{return json.writeValueAsString(value);}
    catch(JsonProcessingException failure){throw new IllegalStateException("UDP_IDENTITY_EVIDENCE_INVALID",failure);}}
  private static IllegalArgumentException invalid(){return new IllegalArgumentException("UDP_IDENTITY_HANDOFF_MISMATCH");}
  public record Result(String outcome,UUID targetUrbanObjectId,boolean duplicate){}
}
