package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit preactivation rebuild. It is not called by per-object ingestion. */
@Service
public class GovernedIdentityIndexBackfill {
  private final JdbcClient db;
  private final ObjectMapper json;
  private final GovernedIdentityScopeLock locks;
  public GovernedIdentityIndexBackfill(JdbcClient db,ObjectMapper json,GovernedIdentityScopeLock locks){
    this.db=db;this.json=json;this.locks=locks;
  }

  /** Scans the scope once under its writer lock; aborts rather than certify incomplete shapes. */
  @Transactional public Result rebuild(GovernedIdentityEngine.Policy policy){
    Objects.requireNonNull(policy);
    locks.acquire(policy.tenantId(),policy.canonicalClass());
    Map<String,GovernedIdentityEngine.Signal> signals=new HashMap<>();
    policy.signals().forEach(signal->signals.put(signal.id(),signal));
    db.sql("delete from ouf_udp.identity_lookup_token where tenant_id=:tenant and canonical_class=:type and policy_ref=:policy and policy_version=:version")
        .param("tenant",policy.tenantId()).param("type",policy.canonicalClass())
        .param("policy",policy.ref()).param("version",policy.version()).update();
    db.sql("delete from ouf_udp.identity_lookup_shape where tenant_id=:tenant and canonical_class=:type and policy_ref=:policy and policy_version=:version")
        .param("tenant",policy.tenantId()).param("type",policy.canonicalClass())
        .param("policy",policy.ref()).param("version",policy.version()).update();
    UUID after=null;long indexed=0;
    while(true){
      String sql="select urban_object_id,current_revision_id from ouf_udp.urban_object where tenant_id=:tenant and canonical_type=:type and status='ACTIVE' "
          +(after==null?"":"and urban_object_id>:after ")+"order by urban_object_id limit 200";
      var query=db.sql(sql).param("tenant",policy.tenantId()).param("type",policy.canonicalClass());
      if(after!=null)query=query.param("after",after);
      List<Map<String,Object>> page=query.query().listOfRows();
      if(page.isEmpty())break;
      for(var object:page){
        UUID id=(UUID)object.get("urban_object_id"),revision=(UUID)object.get("current_revision_id");
        if(revision==null)throw invalid("UNMATERIALIZED_OBJECT");
        indexObject(policy,signals,id,revision);
        indexed++;after=id;
      }
    }
    String shape=ScopedIdentityCandidateRepository.hash(String.join("\u0000",new TreeSet<>(signals.keySet())));
    String ref="coverage://"+UUID.randomUUID();
    db.sql("insert into ouf_udp.identity_lookup_coverage(tenant_id,canonical_class,policy_ref,policy_version,coverage_ref,policy_fingerprint,field_set_hash,indexed_objects,complete) values(:tenant,:type,:policy,:version,:ref,:fingerprint,:shape,:count,true) on conflict(tenant_id,canonical_class,policy_ref,policy_version) do update set coverage_ref=excluded.coverage_ref,policy_fingerprint=excluded.policy_fingerprint,field_set_hash=excluded.field_set_hash,indexed_objects=excluded.indexed_objects,complete=true,updated_at=transaction_timestamp()")
        .param("tenant",policy.tenantId()).param("type",policy.canonicalClass())
        .param("policy",policy.ref()).param("version",policy.version())
        .param("ref",ref).param("fingerprint",ScopedIdentityCandidateRepository.fingerprint(policy))
        .param("shape",shape).param("count",indexed).update();
    return new Result(ref,indexed);
  }
  private void indexObject(GovernedIdentityEngine.Policy policy,
      Map<String,GovernedIdentityEngine.Signal> signals,UUID id,UUID revision){
    String payload=db.sql("select canonical_payload::text from ouf_udp.urban_object_current_state where urban_object_id=:id and revision_id=:revision")
        .param("id",id).param("revision",revision).query(String.class).list().stream().findFirst()
        .orElseThrow(()->invalid("CURRENT_STATE_MISSING"));
    Map<String,Object> current;
    try{current=json.readValue(payload,new TypeReference<>(){});}
    catch(Exception failure){throw invalid("CURRENT_STATE_INVALID");}
    String shape=ScopedIdentityCandidateRepository.hash(String.join("\u0000",new TreeSet<>(current.keySet())));
    List<Map<String,Object>> properties=db.sql("select p.property_iri,p.value_json::text value_json,c.provenance_json #>> '{contractRefs,semanticPublicationSetRef}' publication_ref from ouf_udp.property_value p join ouf_udp.property_contribution c on c.contribution_id=p.contribution_id where p.revision_id=:revision")
        .param("revision",revision).query().listOfRows();
    Set<String> found=new HashSet<>();
    for(var row:properties){
      String property=(String)row.get("property_iri");
      var signal=signals.get(property);
      if(!found.add(property))throw invalid("PROPERTY_DUPLICATE");
      if(signal==null)continue; // Its shape makes it a bounded uncertain candidate.
      if(!signal.semanticRef().equals(property+"@"+row.get("publication_ref")))
        throw invalid("SEMANTIC_COVERAGE_UNVERIFIED");
      Object scalar;
      try{scalar=json.readValue((String)row.get("value_json"),Object.class);}
      catch(Exception failure){throw invalid("VALUE_INVALID");}
      if(!(scalar instanceof String||scalar instanceof Number))throw invalid("VALUE_UNSUPPORTED");
      String normalized;
      try{normalized=GovernedIdentityEngine.normalize(signal.comparator(),String.valueOf(scalar));}
      catch(IllegalArgumentException failure){throw invalid("COMPARATOR_UNSUPPORTED");}
      Object canonical=current.get(property);
      if(!(canonical instanceof String||canonical instanceof Number))throw invalid("CURRENT_VALUE_UNSUPPORTED");
      String canonicalNormalized;
      try{canonicalNormalized=GovernedIdentityEngine.normalize(signal.comparator(),String.valueOf(canonical));}
      catch(IllegalArgumentException failure){throw invalid("CURRENT_VALUE_UNSUPPORTED");}
      if(!normalized.equals(canonicalNormalized))throw invalid("CURRENT_VALUE_MISMATCH");
      db.sql("insert into ouf_udp.identity_lookup_token(tenant_id,canonical_class,policy_ref,policy_version,urban_object_id,revision_id,property_iri,semantic_ref,comparator,value_hash) values(:tenant,:type,:policy,:version,:id,:revision,:property,:semantic,:comparator,:hash)")
          .param("tenant",policy.tenantId()).param("type",policy.canonicalClass())
          .param("policy",policy.ref()).param("version",policy.version())
          .param("id",id).param("revision",revision).param("property",property)
          .param("semantic",signal.semanticRef()).param("comparator",signal.comparator().name())
          .param("hash",ScopedIdentityCandidateRepository.hash(normalized)).update();
    }
    if(!found.equals(current.keySet()))throw invalid("PROPERTY_COVERAGE_UNVERIFIED");
    db.sql("insert into ouf_udp.identity_lookup_shape(tenant_id,canonical_class,policy_ref,policy_version,urban_object_id,revision_id,field_set_hash) values(:tenant,:type,:policy,:version,:id,:revision,:shape)")
        .param("tenant",policy.tenantId()).param("type",policy.canonicalClass())
        .param("policy",policy.ref()).param("version",policy.version())
        .param("id",id).param("revision",revision).param("shape",shape).update();
  }
  private static IllegalStateException invalid(String reason){return new IllegalStateException("UDP_IDENTITY_BACKFILL_"+reason);}
  public record Result(String coverageRef,long indexedObjects){}
}
