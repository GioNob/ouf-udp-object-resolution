package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.ResourceContext;
import java.sql.Types;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Evidence from the UDP owner; durable intake, resolution and materialization remain distinct. */
@Service
public class AcquisitionEvidenceService {
  public static final Set<String> CAPABILITIES=Set.of("urban.object.read","lineage.object.read","lineage.property.read");
  private final JdbcClient db;private final String installationTenant;
  public AcquisitionEvidenceService(JdbcClient db,@Value("${ouf.udp.lake.tenant-id:}")String tenant){this.db=db;this.installationTenant=tenant;}
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=10)
  public Map<String,Object> read(AcquisitionEvidenceApi.Query q,ServingAuthorizationContext actor){
    if(installationTenant.isBlank()||!installationTenant.equals(actor.tenantId()))throw denied();
    require(q,actor,null,"OPEN",Map.of());
    var rows=db.sql("""
      select h.handoff_id,h.ingestion_run_id,h.state intake_state,h.content_hash,h.received_at,h.processed_at,h.failure_code,
        h.receipt_ref _receipt,h.payload_json->>'lineageId' lineage_id,
        h.payload_json->'dataAccessLabels' _labels,j.state job_state,j.attempts job_attempts,
        d.outcome resolution_outcome,d.resolution_decision_id,
        o.observation_id,o.urban_object_id,o.revision_id,o.material_change,
        u.revision current_revision,u.status object_status,
        u.tenant_id _object_tenant,raw.tenant_id _raw_tenant,
        exists(select 1 from ouf_udp.source_binding b where b.source_id=h.source_id and b.type_code=h.type_code
          and b.source_object_id=h.source_object_id and b.urban_object_id=o.urban_object_id) source_binding_matches_observation,
        (select count(*) from ouf_udp.resolution_issue i where i.handoff_id=h.handoff_id and i.state='OPEN') open_resolution_issues,
        transaction_timestamp() snapshot_observed_at,current_setting('transaction_read_only')='on' read_only_snapshot
      from ouf_udp.handoff_intake h left join ouf_udp.materialization_job j using(handoff_id)
        left join ouf_udp.resolution_decision d using(handoff_id)
        left join ouf_udp.materialization_observation o using(handoff_id)
        left join ouf_udp.urban_object u on u.urban_object_id=o.urban_object_id
        left join ouf_udp.lake_object raw on raw.lake_object_id=h.raw_lake_object_id
      where h.source_id=:s and h.ingestion_run_id=:r and (cast(:a as text) is null or h.handoff_id>cast(:a as text))
      order by h.handoff_id limit :n
      """).param("s",q.sourceId()).param("r",q.runId().toString()).param("a",q.afterHandoffId(),Types.VARCHAR).param("n",q.limit()+1).query().listOfRows();
    boolean more=rows.size()>q.limit();if(more)rows=rows.subList(0,q.limit());
    var items=new ArrayList<Map<String,Object>>();boolean redacted=false,propertiesTruncated=false;
    var redactionReasons=new TreeSet<String>();
    for(var row:rows){
      // Historical intake has no tenant column. Use retained RAW ownership and/or the observed object,
      // reject disagreement, and never treat installation configuration alone as row ownership.
      Object rawTenant=row.get("_raw_tenant"),objectTenant=row.get("_object_tenant");
      if(rawTenant==null&&objectTenant==null||rawTenant!=null&&!actor.tenantId().equals(rawTenant)
          ||objectTenant!=null&&!actor.tenantId().equals(objectTenant)){
        redacted=true;redactionReasons.add("RETAINED_TENANT_NOT_VERIFIED");continue;
      }
      String hidden=labelRedactionReason(q,actor,row);
      if(hidden!=null){redacted=true;redactionReasons.add(hidden);continue;}
      UUID object=(UUID)row.get("urban_object_id");
      if(object!=null&&!permitted(q,actor,object,"OPEN",Map.of())){redacted=true;redactionReasons.add("OBJECT_NOT_AUTHORIZED");continue;}
      var item=new LinkedHashMap<>(row);item.remove("_labels");item.remove("_object_tenant");item.remove("_raw_tenant");
      Object receipt=item.remove("_receipt");if(receipt instanceof String s&&!s.isBlank()&&s.length()<=4096)item.put("receiptHash",hash(s));
      item.put("durableIntakeRecorded",receipt instanceof String s&&!s.isBlank());
      item.put("materializationObserved",row.get("observation_id")!=null&&row.get("revision_id")!=null);
      var properties=db.sql("""
        select c.contribution_id,c.property_iri,c.access_label,c.value_hash,
          c.provenance_json->>'lineageRef' lineage_ref,
          c.provenance_json->>'ingestionRunId' provenance_run_id,
          c.provenance_json#>>'{contractRefs,bundleRef}' bundle_ref,
          case when c.value_json='null'::jsonb then 'NULL' when c.value_json='""'::jsonb then 'EMPTY_STRING'
            when jsonb_typeof(c.value_json)='string' and btrim(c.value_json#>>'{}')='' then 'WHITESPACE_ONLY' else 'VALUE' end value_state,
          exists(select 1 from ouf_udp.property_value v where v.contribution_id=c.contribution_id and v.revision_id=:revision) selected_in_observed_revision,
          exists(select 1 from ouf_udp.property_value v join ouf_udp.urban_object current_object on current_object.current_revision_id=v.revision_id
            where v.contribution_id=c.contribution_id and current_object.urban_object_id=:object) selected_in_current_revision
        from ouf_udp.property_contribution c join ouf_udp.urban_object u using(urban_object_id)
        where c.handoff_id=:h and u.tenant_id=:t and c.source_id=:s and c.urban_object_id=:object
        order by c.property_iri limit 201
        """).param("h",row.get("handoff_id")).param("t",actor.tenantId()).param("s",q.sourceId())
        .param("object",object,Types.OTHER).param("revision",row.get("revision_id"),Types.OTHER).query().listOfRows();
      boolean truncated=properties.size()>200;if(truncated){properties=properties.subList(0,200);propertiesTruncated=true;}
      var visible=new ArrayList<Map<String,Object>>();
      for(var p:properties){if(permitted(q,actor,object,String.valueOf(p.get("access_label")),Map.of("propertyIri",String.valueOf(p.get("property_iri")))))visible.add(p);else {redacted=true;redactionReasons.add("PROPERTY_NOT_AUTHORIZED");}}
      item.put("properties",visible);item.put("propertiesTruncated",truncated);items.add(item);
    }
    require(q,actor,null,"OPEN",Map.of());
    var out=new LinkedHashMap<String,Object>();out.put("schema","ouf.udp.acquisition-evidence.v1");out.put("sourceId",q.sourceId());out.put("runId",q.runId());out.put("items",items);
    // Never return a hidden handoff ID as the page token.
    out.put("nextHandoffId",more&&!redacted?rows.getLast().get("handoff_id"):null);
    out.put("redactionReasons",List.copyOf(redactionReasons));
    out.put("partial",more||redacted||propertiesTruncated);out.put("authorization",redacted?"REDACTED":"AUTHORIZED");
    out.put("authorizationDecisionRef",actor.owner().decisionRef());out.put("ingestionAckProven",false);return out;
  }
  // Fixed diagnostic categories only: never serialize denied labels, row IDs or counts.
  private String labelRedactionReason(AcquisitionEvidenceApi.Query q,ServingAuthorizationContext actor,Map<String,Object> row){
    Object raw=row.get("_labels");if(raw==null)return "DATA_LABEL_METADATA_INVALID";
    try{
      Object parsed=new ObjectMapper().readValue(raw.toString(),Object.class);
      if(!(parsed instanceof List<?> list)||list.isEmpty())return "DATA_LABEL_METADATA_INVALID";
      for(Object entry:list){
        if(!(entry instanceof Map<?,?> m)||!(m.get("labelId") instanceof String label)
            ||!Set.of("OPEN","ANONYMOUS","PERSONAL","SENSITIVE","RESTRICTED").contains(label))
          return "DATA_LABEL_METADATA_INVALID";
        if(!permitted(q,actor,null,label,Map.of()))return "DATA_LABEL_NOT_AUTHORIZED";
      }
      return null;
    }catch(java.io.IOException bad){throw new IllegalStateException("UDP_ACQUISITION_METADATA_INVALID");}
  }
  private void require(AcquisitionEvidenceApi.Query q,ServingAuthorizationContext actor,UUID object,String label,Map<String,String> extra){if(!permitted(q,actor,object,label,extra))throw denied();}
  private boolean permitted(AcquisitionEvidenceApi.Query q,ServingAuthorizationContext actor,UUID object,String label,Map<String,String> extra){
    if(actor.owner()==null||!Set.of("OPEN","ANONYMOUS","PERSONAL","SENSITIVE","RESTRICTED").contains(label))return false;
    var attrs=new HashMap<>(extra);attrs.put("module","UDP");attrs.put("sourceRef",q.sourceId());attrs.put("jobRef",q.runId().toString());attrs.put("detailLevel","TENANT_OPERATIONAL");attrs.put("dataAccessLabel",label);attrs.put("requiresDataAccessLabel","true");
    var resource=new ResourceContext(object==null?"operational":"object",object==null?q.runId().toString():object.toString(),actor.tenantId(),null,attrs);
    return CAPABILITIES.stream().allMatch(cap->{var d=actor.owner().decide(cap,resource);return d.allowed()&&"TENANT_OPERATIONAL".equals(d.permittedDetailLevel());});
  }
  static String hash(String value){try{return "sha256:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
  static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"UDP_ACQUISITION_NOT_AUTHORIZED");}
}
