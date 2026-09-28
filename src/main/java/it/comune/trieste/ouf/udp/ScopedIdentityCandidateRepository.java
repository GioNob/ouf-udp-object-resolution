package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Complete tenant/class snapshot scan through the existing scoped B-tree index. */
@Repository
public class ScopedIdentityCandidateRepository {
  private final JdbcClient db;
  private final ObjectMapper json;
  public ScopedIdentityCandidateRepository(JdbcClient db,ObjectMapper json){this.db=db;this.json=json;}

  /** One statement sees one PostgreSQL snapshot. Callers must also serialize identity writes in the scope. */
  public GovernedIdentityEngine.Candidates retrieve(GovernedIdentityEngine.Policy policy) {
    Objects.requireNonNull(policy);
    String sql="""
        with scoped as (
          select urban_object_id,tenant_id,canonical_type,current_revision_id
          from ouf_udp.urban_object
          where tenant_id=:tenant and canonical_type=:canonicalClass and status='ACTIVE'
          order by urban_object_id limit :candidateLimit
        ), snapshot as (select pg_current_snapshot()::text snapshot_ref)
        select snapshot.snapshot_ref,o.urban_object_id,o.tenant_id,o.canonical_type,
               p.property_iri,p.value_json::text value_json,p.contribution_id,
               c.provenance_json #>> '{contractRefs,semanticPublicationSetRef}' publication_ref
        from snapshot left join scoped o on true
          left join ouf_udp.property_value p on p.revision_id=o.current_revision_id
          left join ouf_udp.property_contribution c on c.contribution_id=p.contribution_id
        order by o.urban_object_id,p.property_iri
        """;
    var query=db.sql(sql).param("tenant",policy.tenantId()).param("canonicalClass",policy.canonicalClass())
        .param("candidateLimit",policy.maxCandidates()+1);
    List<Map<String,Object>> rows=query.query().listOfRows();
    if(rows.isEmpty())throw new IllegalStateException("UDP_CANDIDATE_SNAPSHOT_MISSING");
    String snapshot=String.valueOf(rows.getFirst().get("snapshot_ref"));
    Map<UUID,Map<String,GovernedIdentityEngine.Value>> values=new LinkedHashMap<>();
    for(var row:rows){
      UUID object=(UUID)row.get("urban_object_id");
      if(object==null)continue;
      Map<String,GovernedIdentityEngine.Value> found=values.computeIfAbsent(object,ignored->new HashMap<>());
      if(row.get("property_iri") instanceof String property) {
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
        policy.canonicalClass(),"postgres-snapshot://"+snapshot,
        candidates.size()<=policy.maxCandidates(),candidates);
  }

  private String scalar(Object encoded){
    if(!(encoded instanceof String text))return null;
    try{Object value=json.readValue(text,Object.class);
      return value instanceof String || value instanceof Number ? String.valueOf(value):null;
    }catch(Exception failure){throw new IllegalStateException("UDP_INDEXED_VALUE_INVALID",failure);}
  }
}
