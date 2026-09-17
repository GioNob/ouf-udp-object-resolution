package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** A human decision concerns this exact pair under this policy, never a global source priority. */
@Service
public class GeometryGovernanceService {
  private final JdbcClient db;private final ObjectMapper json;
  public GeometryGovernanceService(JdbcClient db,ObjectMapper json){this.db=db;this.json=json;}

  @Transactional(readOnly=true) public Map<String,Object> review(UUID issueId,ServingAuthorizationContext auth){
    auth.require("resolution.issue.read");var issue=issue(issueId,false);UUID object=(UUID)issue.get("source_object_id");
    authorizeObject(object,auth);var evidence=read(String.valueOf(issue.get("evidence")));
    UUID previous=UUID.fromString(String.valueOf(evidence.get("currentGeometryRevision")));
    UUID candidate=UUID.fromString(String.valueOf(evidence.get("candidateGeometryRevision")));
    var before=geometry(previous,object,auth,"resolution.issue.read");var after=geometry(candidate,object,auth,"resolution.issue.read");
    return Map.of("issueId",issueId,"objectId",object,"state",issue.get("state"),"policyRef",evidence.get("policyRef"),"propertyIri",evidence.get("geometryProperty"),"current",before,"candidate",after,"actions",List.of("KEEP_CURRENT","ACCEPT_CANDIDATE"));
  }

  @Transactional public UUID decide(UUID issueId,UUID expectedCurrent,UUID chosen,String reason,
      TrustedHumanContext actor,ServingAuthorizationContext auth){
    actor.require("authority.override");auth.require("authority.override");
    if(!actor.subject().equals(auth.subject())||!actor.tenantId().equals(auth.tenantId()))throw new SecurityException("UDP_AUTHORIZATION_CONTEXT_MISMATCH");
    if(reason==null||reason.isBlank()||reason.length()>2000)throw new IllegalArgumentException("UDP_REASON_REQUIRED");
    var issue=issue(issueId,true);UUID object=(UUID)issue.get("source_object_id");authorizeObject(object,auth);
    // Serialize decisions and current-view materialization on this object.
    db.sql("select urban_object_id from ouf_udp.urban_object where urban_object_id=:u for update").param("u",object).query(UUID.class).single();
    var evidence=read(String.valueOf(issue.get("evidence")));
    UUID previous=UUID.fromString(String.valueOf(evidence.get("currentGeometryRevision"))),candidate=UUID.fromString(String.valueOf(evidence.get("candidateGeometryRevision")));
    geometry(previous,object,auth,"authority.override");geometry(candidate,object,auth,"authority.override");
    if(chosen==null||!Set.of(previous,candidate).contains(chosen)||!previous.equals(expectedCurrent))throw conflict();
    var existing=db.sql("select decision_id,chosen_geometry_revision,actor_subject,reason from ouf_udp.human_geometry_decision where issue_id=:i").param("i",issueId).query().listOfRows();
    if(!existing.isEmpty()){
      var old=existing.getFirst();if(chosen.equals(old.get("chosen_geometry_revision"))&&actor.subject().equals(old.get("actor_subject"))&&reason.equals(old.get("reason")))return (UUID)old.get("decision_id");throw conflict();
    }
    UUID current=db.sql("select geometry_revision_id from ouf_udp.urban_geometry_current where urban_object_id=:u for update").param("u",object).query(UUID.class).single();
    if(!"OPEN".equals(issue.get("state"))||!previous.equals(current))throw conflict();
    UUID decision=UUID.randomUUID();
    db.sql("insert into ouf_udp.human_geometry_decision(decision_id,issue_id,urban_object_id,property_iri,policy_ref,previous_geometry_revision,candidate_geometry_revision,chosen_geometry_revision,actor_subject,authorization_decision_ref,reason,correlation_id) values(:d,:i,:u,:p,:policy,:old,:candidate,:chosen,:actor,:auth,:reason,:correlation)")
      .param("d",decision).param("i",issueId).param("u",object).param("p",evidence.get("geometryProperty")).param("policy",evidence.get("policyRef")).param("old",previous).param("candidate",candidate).param("chosen",chosen).param("actor",actor.subject()).param("auth",auth.decisionRef("authority.override")).param("reason",reason).param("correlation",actor.correlationId()).update();
    db.sql("update ouf_udp.spatial_resolution_issue set state='RESOLVED' where issue_id=:i").param("i",issueId).update();
    db.sql("update ouf_udp.materialization_job set state='READY',state_version=state_version+1,claimed_by=null,lease_until=null,safe_failure_code=null,updated_at=transaction_timestamp() where handoff_id=:h and state='QUARANTINED' and safe_failure_code='UDP_SPATIAL_AUTHORITY_CONFLICT'").param("h",issue.get("handoff_id")).update();
    db.sql("insert into ouf_udp.governance_audit(audit_id,action,outcome,actor_type,actor_subject,tenant_id,capability,authorization_decision_ref,reason,correlation_id,evidence_hash) values(gen_random_uuid(),'UDP_AUTHORITY_OVERRIDE','APPROVED','HUMAN',:a,:tenant,'authority.override',:auth,:reason,:correlation,:evidence)")
      .param("a",actor.subject()).param("tenant",actor.tenantId()).param("auth",auth.decisionRef("authority.override")).param("reason",reason).param("correlation",actor.correlationId()).param("evidence",evidenceHash(decision,previous,candidate,chosen)).update();
    return decision;
  }
  private Map<String,Object> issue(UUID id,boolean lock){return db.sql("select issue_id,handoff_id,source_object_id,state,evidence_json::text evidence from ouf_udp.spatial_resolution_issue where issue_id=:i and reason_code='SPATIAL_AUTHORITY_CONFLICT'"+(lock?" for update":"")).param("i",id).query().listOfRows().stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"UDP_GEOMETRY_ISSUE_NOT_FOUND"));}
  private void authorizeObject(UUID id,ServingAuthorizationContext auth){
    if(db.sql("select 1 from ouf_udp.urban_object where urban_object_id=:u and tenant_id=:t and status='ACTIVE'").param("u",id).param("t",auth.tenantId()).query(Integer.class).optional().isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"UDP_OBJECT_NOT_FOUND");
  }
  private Map<String,Object> geometry(UUID revision,UUID object,ServingAuthorizationContext auth,String capability){
    var row=db.sql("select g.access_label,h.source_id,h.ingestion_run_id,ST_AsGeoJSON(g.geometry) geojson,g.source_geometry_json::text original,g.evidence_json::text provenance from ouf_udp.urban_geometry g join ouf_udp.handoff_intake h on h.handoff_id=g.handoff_id where g.geometry_revision_id=:r and g.urban_object_id=:u").param("r",revision).param("u",object).query().singleRow();
    var scope=Map.of("sourceRef",String.valueOf(row.get("source_id")),"jobRef",String.valueOf(row.get("ingestion_run_id")),"revisionRef",revision.toString(),"projection","geometry");
    String label=String.valueOf(row.get("access_label"));if(!auth.allowedDataLabels().contains(label))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"UDP_GEOMETRY_PROFILE_LABEL_DENIED");auth.requireResource(capability,"object",object,label,scope);auth.requireResource("urban.geometry.read","object",object,label,scope);
    return Map.of("revisionId",revision,"sourceId",row.get("source_id"),"crs","EPSG:4326","geometry",read(String.valueOf(row.get("geojson"))),"original",read(String.valueOf(row.get("original"))),"provenance",read(String.valueOf(row.get("provenance"))));
  }
  private Map<String,Object> read(String value){try{return json.readValue(value,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException("UDP_STORED_JSON_INVALID",e);}}
  private static String evidenceHash(UUID decision,UUID previous,UUID candidate,UUID chosen){try{return "sha256:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((decision+"|"+previous+"|"+candidate+"|"+chosen).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
  private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"UDP_GEOMETRY_DECISION_CONFLICT");}
}
