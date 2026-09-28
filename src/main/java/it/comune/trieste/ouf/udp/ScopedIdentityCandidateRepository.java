package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** One snapshot, indexed union of every exposed comparable canonical value. */
@Repository
public class ScopedIdentityCandidateRepository {
  private final JdbcClient db;
  private final ObjectMapper json;
  public ScopedIdentityCandidateRepository(JdbcClient db,ObjectMapper json){this.db=db;this.json=json;}

  public GovernedIdentityEngine.Candidates retrieve(GovernedIdentityEngine.Policy policy,
      GovernedIdentityEngine.Subject subject) {
    Objects.requireNonNull(policy);Objects.requireNonNull(subject);
    if(!policy.tenantId().equals(subject.tenantId())
        || !policy.canonicalClass().equals(subject.canonicalClass())
        || !policy.sourceId().equals(subject.sourceId()))throw new IllegalArgumentException("UDP_IDENTITY_SCOPE_INVALID");
    Map<String,GovernedIdentityEngine.Signal> signals=new HashMap<>();
    policy.signals().forEach(signal->signals.put(signal.id(),signal));
    List<Map<String,String>> seeds=new ArrayList<>();
    for(var entry:subject.values().entrySet()){
      var signal=signals.get(entry.getKey());
      if(signal==null||!signal.semanticRef().equals(entry.getValue().semanticRef()))
        throw new IllegalArgumentException("UDP_IDENTITY_SEED_INVALID");
      try{
        seeds.add(Map.of("property_iri",signal.id(),"semantic_ref",signal.semanticRef(),
            "comparator",signal.comparator().name(),"value_hash",hash(
                GovernedIdentityEngine.normalize(signal.comparator(),entry.getValue().raw()))));
      }catch(IllegalArgumentException unsupported){return incomplete(policy);}
    }
    if(seeds.isEmpty())return incomplete(policy);
    String shape=hash(String.join("\u0000",new TreeSet<>(subject.values().keySet())));
    String policyShape=hash(String.join("\u0000",new TreeSet<>(signals.keySet())));
    String sql="""
        with coverage as (
          select coverage_ref from ouf_udp.identity_lookup_coverage
          where tenant_id=:tenant and canonical_class=:canonicalClass
            and policy_ref=:policy and policy_version=:version
            and policy_fingerprint=:fingerprint and field_set_hash=:policyShape and complete
        ), seeds as (
          select * from jsonb_to_recordset(cast(:seeds as jsonb))
            as s(property_iri text,semantic_ref text,comparator text,value_hash text)
        ), equal_hits as (
          select distinct t.urban_object_id
          from seeds s join ouf_udp.identity_lookup_token t
            on t.tenant_id=:tenant and t.canonical_class=:canonicalClass
            and t.policy_ref=:policy and t.policy_version=:version
            and t.property_iri=s.property_iri and t.semantic_ref=s.semantic_ref
            and t.comparator=s.comparator and t.value_hash=s.value_hash
          join coverage on true
          join ouf_udp.urban_object active_object on active_object.urban_object_id=t.urban_object_id
            and active_object.current_revision_id=t.revision_id and active_object.status='ACTIVE'
          order by t.urban_object_id limit :candidateLimit
        ), shape_hits as (
          select urban_object_id from (
            select s.urban_object_id from ouf_udp.identity_lookup_shape s
            join coverage on true
            join ouf_udp.urban_object active_object on active_object.urban_object_id=s.urban_object_id
              and active_object.current_revision_id=s.revision_id and active_object.status='ACTIVE'
            where s.tenant_id=:tenant and s.canonical_class=:canonicalClass
              and s.policy_ref=:policy and s.policy_version=:version
              and s.field_set_hash < :shape
            order by s.field_set_hash,s.urban_object_id limit :candidateLimit
          ) before_shapes
          union all
          select urban_object_id from (
            select s.urban_object_id from ouf_udp.identity_lookup_shape s
            join coverage on true
            join ouf_udp.urban_object active_object on active_object.urban_object_id=s.urban_object_id
              and active_object.current_revision_id=s.revision_id and active_object.status='ACTIVE'
            where s.tenant_id=:tenant and s.canonical_class=:canonicalClass
              and s.policy_ref=:policy and s.policy_version=:version
              and s.field_set_hash > :shape
            order by s.field_set_hash,s.urban_object_id limit :candidateLimit
          ) after_shapes
        ), hits as (
          select distinct urban_object_id from (
            select urban_object_id from equal_hits
            union all select urban_object_id from shape_hits
          ) selected order by urban_object_id limit :candidateLimit
        ), snapshot as (select pg_current_snapshot()::text snapshot_ref)
        select coverage.coverage_ref,snapshot.snapshot_ref,o.urban_object_id,
               p.property_iri,p.value_json::text value_json,p.contribution_id,
               c.provenance_json #>> '{contractRefs,semanticPublicationSetRef}' publication_ref
        from snapshot left join coverage on true left join hits h on true
          left join ouf_udp.urban_object o on o.urban_object_id=h.urban_object_id
          left join ouf_udp.property_value p on p.revision_id=o.current_revision_id
          left join ouf_udp.property_contribution c on c.contribution_id=p.contribution_id
        order by o.urban_object_id,p.property_iri
        """;
    List<Map<String,Object>> rows=db.sql(sql).param("tenant",policy.tenantId())
        .param("canonicalClass",policy.canonicalClass()).param("policy",policy.ref())
        .param("version",policy.version()).param("shape",shape).param("policyShape",policyShape)
        .param("fingerprint",fingerprint(policy))
        .param("seeds",write(seeds))
        .param("candidateLimit",policy.maxCandidates()+1).query().listOfRows();
    if(rows.isEmpty())throw new IllegalStateException("UDP_CANDIDATE_SNAPSHOT_MISSING");
    String coverage=(String)rows.getFirst().get("coverage_ref");
    if(coverage==null)return incomplete(policy);
    Map<UUID,Map<String,GovernedIdentityEngine.Value>> values=new LinkedHashMap<>();
    for(var row:rows){
      UUID object=(UUID)row.get("urban_object_id");
      if(object==null)continue;
      Map<String,GovernedIdentityEngine.Value> found=values.computeIfAbsent(object,ignored->new HashMap<>());
      if(row.get("property_iri") instanceof String property){
        String publication=row.get("publication_ref") instanceof String ref && !ref.isBlank()?ref:"unverified";
        UUID contribution=(UUID)row.get("contribution_id");
        String raw=scalar(row.get("value_json"));
        boolean supported=raw!=null;
        var value=new GovernedIdentityEngine.Value(property+"@"+(supported?publication:"unsupported"),
            supported?raw:String.valueOf(row.get("value_json")),
            contribution==null?"unverified://"+object+"/"+property:"contribution://"+contribution);
        if(found.putIfAbsent(property,value)!=null)
          found.put(property,new GovernedIdentityEngine.Value(property+"@unsupported",value.raw(),value.provenanceRef()));
      }
    }
    List<GovernedIdentityEngine.Candidate> candidates=new ArrayList<>();
    for(var entry:values.entrySet())candidates.add(new GovernedIdentityEngine.Candidate(entry.getKey(),
        policy.tenantId(),policy.canonicalClass(),entry.getValue()));
    return new GovernedIdentityEngine.Candidates(policy.ref(),policy.version(),policy.tenantId(),
        policy.canonicalClass(),"indexed-snapshot://"+coverage+"/"+rows.getFirst().get("snapshot_ref"),
        candidates.size()<=policy.maxCandidates(),candidates);
  }

  private GovernedIdentityEngine.Candidates incomplete(GovernedIdentityEngine.Policy policy){
    return new GovernedIdentityEngine.Candidates(policy.ref(),policy.version(),policy.tenantId(),
        policy.canonicalClass(),null,false,List.of());
  }
  private String write(Object value){try{return json.writeValueAsString(value);}
    catch(Exception failure){throw new IllegalStateException("UDP_IDENTITY_SEED_ENCODING_FAILED",failure);}}
  private String scalar(Object encoded){
    if(!(encoded instanceof String text))return null;
    try{Object value=json.readValue(text,Object.class);
      return value instanceof String || value instanceof Number ? String.valueOf(value):null;
    }catch(Exception failure){throw new IllegalStateException("UDP_INDEXED_VALUE_INVALID",failure);}
  }
  static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8)));}
    catch(Exception failure){throw new IllegalStateException("UDP_IDENTITY_HASH_FAILED",failure);}}
  static String fingerprint(GovernedIdentityEngine.Policy policy){
    List<String> parts=policy.signals().stream()
        .map(signal->signal.id()+"\u0000"+signal.semanticRef()+"\u0000"+signal.comparator().name()
            +"\u0000"+signal.assertionRef())
        .sorted().toList();
    List<String> rules=policy.sufficientRules().stream()
        .map(rule->rule.id()+"\u0000"+rule.assertionRef()+"\u0000"
            +String.join("\u0000",new TreeSet<>(rule.signalIds()))).sorted().toList();
    return hash(policy.allowAutoNew()+"\u0002"+String.join("\u0001",parts)
        +"\u0002"+String.join("\u0001",rules));
  }
}
