package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persists only non-automatic decisions for the existing HUMAN review path. */
@Repository
public class GovernedIdentityReviewRepository {
  private final JdbcClient db;
  private final ObjectMapper json;
  public GovernedIdentityReviewRepository(JdbcClient db,ObjectMapper json){this.db=db;this.json=json;}

  @Transactional public UUID record(String handoffId,GovernedIdentityEngine.Decision decision,
      GovernedIdentityEngine.Candidates retrieved){
    if(handoffId==null||handoffId.isBlank()||decision==null||retrieved==null
        || !Set.of(GovernedIdentityEngine.Outcome.REVIEW_REQUIRED,
            GovernedIdentityEngine.Outcome.RESOLUTION_TOO_BROAD).contains(decision.outcome())
        || !decision.policyRef().equals(retrieved.policyRef())
        || !decision.policyVersion().equals(retrieved.policyVersion()))
      throw new IllegalArgumentException("UDP_IDENTITY_REVIEW_INVALID");
    List<Map<String,Object>> prior=db.sql("select resolution_decision_id,policy_ref,strategy_id,strategy_version,outcome from ouf_udp.resolution_decision where handoff_id=:h")
        .param("h",handoffId).query().listOfRows();
    if(!prior.isEmpty()){
      var old=prior.getFirst();
      if(!decision.policyRef().equals(old.get("policy_ref"))
          || !"GOVERNED_IDENTITY".equals(old.get("strategy_id"))
          || !decision.policyVersion().equals(old.get("strategy_version"))
          || !"REVIEW_REQUIRED".equals(old.get("outcome")))
        throw new IllegalStateException("UDP_IDENTITY_REVIEW_CONFLICT");
      return (UUID)old.get("resolution_decision_id");
    }
    UUID id=UUID.randomUUID();
    String coverage=retrieved.coverageRef()==null?"coverage://unverified":retrieved.coverageRef();
    Map<String,Object> evidence=new LinkedHashMap<>();
    evidence.put("policyRef",decision.policyRef());
    evidence.put("policyVersion",decision.policyVersion());
    evidence.put("coverageRef",coverage);
    evidence.put("complete",retrieved.complete());
    evidence.put("outcome",decision.outcome().name());
    evidence.put("reason",decision.reason());
    evidence.put("assessments",decision.assessments());
    String details=write(List.of(evidence));
    // Incomplete coverage cannot present a selectable subset to the HUMAN endpoint.
    List<UUID> selectable=retrieved.complete()
        ?retrieved.rows().stream().map(GovernedIdentityEngine.Candidate::objectId).toList():List.of();
    db.sql("insert into ouf_udp.resolution_decision(resolution_decision_id,handoff_id,candidate_ref,outcome,target_urban_object_id,strategy_id,strategy_version,evidence_refs,decided_by,policy_ref) values(:id,:h,:c,'REVIEW_REQUIRED',null,'GOVERNED_IDENTITY',:v,cast(:e as jsonb),'SERVICE_IDENTITY',:p)")
        .param("id",id).param("h",handoffId).param("c",coverage).param("v",decision.policyVersion())
        .param("e",details).param("p",decision.policyRef()).update();
    db.sql("insert into ouf_udp.resolution_issue(issue_id,resolution_decision_id,handoff_id,reason_code,candidate_refs,evidence_refs) values(gen_random_uuid(),:id,:h,:r,cast(:c as jsonb),cast(:e as jsonb))")
        .param("id",id).param("h",handoffId)
        .param("r",decision.outcome()==GovernedIdentityEngine.Outcome.RESOLUTION_TOO_BROAD
            ?"UDP_RESOLUTION_TOO_BROAD":decision.reason())
        .param("c",write(selectable)).param("e",details).update();
    return id;
  }
  private String write(Object value){try{return json.writeValueAsString(value);}
    catch(JsonProcessingException failure){throw new IllegalStateException("UDP_IDENTITY_EVIDENCE_INVALID",failure);}}
}
