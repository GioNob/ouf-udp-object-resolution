package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit, bounded-by-scope preactivation rebuild of a frozen Onboarding proposal. */
@Service
public class GovernedIdentityPreflight {
  private final JdbcClient db;
  private final ObjectMapper json;
  private final GovernedIdentityIndexBackfill index;
  public GovernedIdentityPreflight(JdbcClient db,ObjectMapper json,GovernedIdentityIndexBackfill index){
    this.db=db;this.json=json;this.index=index;
  }

  @Transactional public Attestation prepare(String sourceId,String configurationHash,
      Map<String,Object> configuration,TrustedHumanContext actor){
    actor.require("authorization.policy.admin");
    if(sourceId==null||sourceId.isBlank()||configuration==null
        ||!hash(configuration).equals(configurationHash))throw invalid("CONFIGURATION_HASH");
    var semantic=object(configuration,"semanticMapping");
    var source=object(semantic,"sourceType");
    if(!sourceId.equals(source.get("sourceId")))throw invalid("SOURCE");
    var udp=object(object(object(configuration,"extractionProfile"),"runtime"),"udp");
    var resolution=object(udp,"resolution");
    var materialization=json.convertValue(object(udp,"materialization"),UdpPorts.MaterializationProfile.class);
    Set<String> mapped=new HashSet<>();
    if(!(semantic.get("propertyMappings") instanceof List<?> mappings)||mappings.isEmpty())throw invalid("MAPPING");
    for(Object entry:mappings){if(!(entry instanceof Map<?,?> mapping)
        ||!(mapping.get("targetPropertyIri") instanceof String iri)||iri.isBlank()
        ||!mapped.add(iri))throw invalid("MAPPING");}
    Set<String> materialized=new HashSet<>();
    for(var property:materialization.properties()){
      if(!property.propertyIri().equals(property.sourceField())
          ||!materialized.add(property.propertyIri()))throw invalid("MATERIALIZATION");
    }
    if(!mapped.equals(materialized))throw invalid("MATERIALIZATION");
    String canonicalClass=PublishedRuntimeConfiguration.text(object(resolution,"governedIdentity"),"canonicalClass");
    if(!(semantic.get("targetClasses") instanceof List<?> classes)
        ||classes.stream().noneMatch(c->c instanceof Map<?,?> target
            &&canonicalClass.equals(target.get("classIri"))))throw invalid("CLASS");
    var policy=PublishedIdentityPolicy.decode(json,resolution,actor.tenantId(),sourceId,
        canonicalClass,mapped);
    var rebuilt=index.rebuild(policy);
    UUID id=UUID.randomUUID();String fingerprint=ScopedIdentityCandidateRepository.fingerprint(policy);
    db.sql("insert into ouf_udp.identity_preactivation_attestation(attestation_id,configuration_hash,tenant_id,source_id,canonical_class,policy_ref,policy_version,policy_fingerprint,coverage_ref,indexed_objects,actor_subject) values(:id,:hash,:tenant,:source,:class,:policy,:version,:fingerprint,:coverage,:count,:actor)")
        .param("id",id).param("hash",configurationHash).param("tenant",actor.tenantId())
        .param("source",sourceId).param("class",canonicalClass).param("policy",policy.ref())
        .param("version",policy.version()).param("fingerprint",fingerprint)
        .param("coverage",rebuilt.coverageRef()).param("count",rebuilt.indexedObjects())
        .param("actor",actor.subject()).update();
    return status(id,actor);
  }

  public Attestation status(UUID id,TrustedHumanContext actor){
    actor.require("authorization.policy.admin");
    var row=db.sql("select a.attestation_id,a.configuration_hash,a.tenant_id,a.source_id,a.canonical_class,a.policy_ref,a.policy_version,a.policy_fingerprint,a.coverage_ref,a.indexed_objects, c.complete and c.coverage_ref=a.coverage_ref and c.policy_fingerprint=a.policy_fingerprint and c.field_set_hash is not null valid from ouf_udp.identity_preactivation_attestation a left join ouf_udp.identity_lookup_coverage c on c.tenant_id=a.tenant_id and c.canonical_class=a.canonical_class and c.policy_ref=a.policy_ref and c.policy_version=a.policy_version where a.attestation_id=:id and a.tenant_id=:tenant")
        .param("id",id).param("tenant",actor.tenantId()).query().listOfRows().stream()
        .findFirst().orElseThrow(()->invalid("NOT_FOUND"));
    return new Attestation((UUID)row.get("attestation_id"),String.valueOf(row.get("configuration_hash")),
        String.valueOf(row.get("tenant_id")),String.valueOf(row.get("source_id")),
        String.valueOf(row.get("canonical_class")),String.valueOf(row.get("policy_ref")),
        String.valueOf(row.get("policy_version")),String.valueOf(row.get("policy_fingerprint")),
        String.valueOf(row.get("coverage_ref")),((Number)row.get("indexed_objects")).longValue(),
        Boolean.TRUE.equals(row.get("valid")));
  }
  private String hash(Object value){try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
      .digest(json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true)
          .writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
    }catch(Exception failure){throw invalid("HASH");}}
  private static Map<String,Object> object(Map<String,Object> parent,String key){
    return PublishedRuntimeConfiguration.object(parent,key);
  }
  private static IllegalArgumentException invalid(String reason){
    return new IllegalArgumentException("UDP_IDENTITY_PREFLIGHT_"+reason);
  }
  public record Attestation(UUID attestationId,String configurationHash,String tenantId,String sourceId,
      String canonicalClass,String policyRef,String policyVersion,String policyFingerprint,
      String coverageRef,long indexedObjects,boolean valid){}
}
