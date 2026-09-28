package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class ScopedIdentityCandidateRepositoryRuntimeTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",()->required("OUF_UDP_DB_URL"));
    r.add("spring.datasource.username",()->required("OUF_UDP_DB_USER"));
    r.add("spring.datasource.password",()->required("OUF_UDP_DB_PASSWORD"));
  }
  @Autowired ScopedIdentityCandidateRepository candidates;
  @Autowired HandoffIntakeService intake;
  @Autowired ObjectResolutionService resolver;
  @Autowired CanonicalMaterializer materializer;
  @Autowired JdbcClient db;
  @org.junit.jupiter.api.BeforeEach void clean(){
    db.sql("truncate table ouf_udp.identity_lookup_token,ouf_udp.identity_lookup_coverage").update();
  }

  @Test void neverInfersCompletenessFromClassSizeOrEmptyResults(){
    var policy=policy();
    var subject=subject("Alpha");
    var result=candidates.retrieve(policy,subject);
    assertThat(result.complete()).isFalse();
    assertThat(result.rows()).isEmpty();
    assertThat(result.coverageRef()).isNull();
    assertThat(new GovernedIdentityEngine().decide(policy,subject,result).outcome())
        .isEqualTo(GovernedIdentityEngine.Outcome.REVIEW_REQUIRED);
    assertThat(new GovernedIdentityEngine().decide(policy,subject,result).reason())
        .isEqualTo("CANDIDATE_COVERAGE_UNVERIFIED");
  }
  @Test void indexedSeedFindsOnlyMatchingCurrentObjectAndMutationInvalidatesCoverage(){
    db.sql("truncate table ouf_udp.property_conflict,ouf_udp.property_value,ouf_udp.materialization_observation,ouf_udp.property_contribution,ouf_udp.object_revision,ouf_udp.resolution_issue,ouf_udp.resolution_decision,ouf_udp.source_binding,ouf_udp.urban_object,ouf_udp.materialization_job,ouf_udp.handoff_event,ouf_udp.handoff_intake restart identity cascade").update();
    UUID matching=materialize("one","Alpha"),other=materialize("two","Beta");
    UUID revision=db.sql("select current_revision_id from ouf_udp.urban_object where urban_object_id=:u")
        .param("u",matching).query(UUID.class).single();
    db.sql("insert into ouf_udp.identity_lookup_token(tenant_id,canonical_class,policy_ref,policy_version,urban_object_id,revision_id,property_iri,semantic_ref,comparator,value_hash) values('default','ouf:Road','policy://identity/1','1',:u,:r,'ouf:name','ouf:name@semantic://publication/1','TEXT_V1',:v)")
        .param("u",matching).param("r",revision).param("v",ScopedIdentityCandidateRepository.hash("alpha")).update();
    UUID otherRevision=db.sql("select current_revision_id from ouf_udp.urban_object where urban_object_id=:u")
        .param("u",other).query(UUID.class).single();
    db.sql("insert into ouf_udp.identity_lookup_token(tenant_id,canonical_class,policy_ref,policy_version,urban_object_id,revision_id,property_iri,semantic_ref,comparator,value_hash) values('default','ouf:Road','policy://identity/1','1',:u,:r,'ouf:name','ouf:name@semantic://publication/1','TEXT_V1',:v)")
        .param("u",other).param("r",otherRevision).param("v",ScopedIdentityCandidateRepository.hash("beta")).update();
    // This marker models a completed activation backfill. Production has no publisher yet.
    db.sql("insert into ouf_udp.identity_lookup_coverage(tenant_id,canonical_class,policy_ref,policy_version,coverage_ref,complete) values('default','ouf:Road','policy://identity/1','1','coverage://test',true)").update();
    var result=candidates.retrieve(policy(),subject("ALPHA"));
    assertThat(result.complete()).isTrue();
    assertThat(result.rows()).extracting(GovernedIdentityEngine.Candidate::objectId).containsExactly(matching);
    assertThat(new GovernedIdentityEngine().decide(policy(),subject("ALPHA"),result).outcome())
        .isEqualTo(GovernedIdentityEngine.Outcome.MATCH);
    db.sql("insert into ouf_udp.urban_object(urban_object_id,tenant_id,canonical_type) values(gen_random_uuid(),'default','ouf:Road')").update();
    assertThat(candidates.retrieve(policy(),subject("ALPHA")).complete()).isFalse();
  }
  private GovernedIdentityEngine.Policy policy(){return new GovernedIdentityEngine.Policy("policy://identity/1","1","default","ouf:Road","registry",1,true,
        List.of(new GovernedIdentityEngine.Signal("ouf:name","ouf:name@semantic://publication/1",
            GovernedIdentityEngine.ComparatorKind.TEXT_V1,false,false,"assertion://name/1")),
        List.of(new GovernedIdentityEngine.SufficientRule("name",Set.of("ouf:name"),"assertion://rule/1")));}
  private GovernedIdentityEngine.Subject subject(String value){return new GovernedIdentityEngine.Subject("default","ouf:Road","registry",Map.of("ouf:name",
      new GovernedIdentityEngine.Value("ouf:name@semantic://publication/1",value,"handoff://probe")));}
  private UUID materialize(String id,String name){
    Map<String,Object> envelope=envelope(id,name);
    intake.accept(envelope);
    UUID object=resolver.resolve("candidate-"+id,envelope,new UdpPorts.ResolutionProfile("CANONICAL_KEY","1","policy://resolution/1","ouf:Road","code","code")).targetUrbanObjectId();
    materializer.materialize("candidate-"+id,object,envelope,new UdpPorts.MaterializationProfile("policy://authority/1",
        List.of(new UdpPorts.PropertyRule("name","ouf:name","string","OPEN",List.of("registry")))));
    return object;
  }
  private Map<String,Object> envelope(String id,String name){return new LinkedHashMap<>(Map.ofEntries(
        Map.entry("handoffId","candidate-"+id),Map.entry("ingestionRunId","run-1"),Map.entry("ingestionId","ing-"+id),
        Map.entry("sourceIdentity",Map.of("sourceId","registry","typeCode","ROAD","sourceObjectId",id,"observedAt","2026-09-12T00:00:00Z")),
        Map.entry("operation","UPSERT"),Map.entry("canonicalPayload",Map.of("code",id,"name",name)),
        Map.entry("rawObjectRef","raw://"+id),Map.entry("contractRefs",Map.of("sourceSchemaRef","schema://road/1","bundleRef","bundle://road/1","semanticPublicationSetRef","semantic://publication/1","adapterProfileRef","adapter://rest/1")),
        Map.entry("lineageId","lineage-"+id),Map.entry("contentHash","sha256:"+id),
        Map.entry("acquiredAt","2026-09-12T00:00:00Z"),Map.entry("changeRepresentation",Map.of("mode","FULL_SNAPSHOT"))));}
  private static String required(String n){String value=System.getenv(n);if(value==null)throw new IllegalStateException(n+" required");return value;}
}
