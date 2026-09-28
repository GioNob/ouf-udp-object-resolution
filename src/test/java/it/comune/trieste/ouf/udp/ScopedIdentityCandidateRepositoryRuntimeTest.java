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
  @Autowired GovernedIdentityIndexBackfill backfill;
  @Autowired JdbcClient db;
  @org.junit.jupiter.api.BeforeEach void clean(){
    db.sql("truncate table ouf_udp.identity_lookup_token,ouf_udp.identity_lookup_shape,ouf_udp.identity_lookup_coverage").update();
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
    clearObjects();
    UUID matching=materialize("one","Alpha");materialize("two","Beta");
    assertThat(backfill.rebuild(policy()).indexedObjects()).isEqualTo(2);
    db.sql("update ouf_udp.identity_lookup_coverage set field_set_hash=:shape where policy_ref='policy://identity/1'")
        .param("shape",ScopedIdentityCandidateRepository.hash("ouf:another-field")).update();
    assertThat(candidates.retrieve(policy(),subject("ALPHA")).complete()).isFalse();
    db.sql("update ouf_udp.identity_lookup_coverage set field_set_hash=:shape where policy_ref='policy://identity/1'")
        .param("shape",ScopedIdentityCandidateRepository.hash("ouf:name")).update();
    var changedComparator=new GovernedIdentityEngine.Policy(policy().ref(),policy().version(),
        policy().tenantId(),policy().canonicalClass(),policy().sourceId(),1,true,
        List.of(new GovernedIdentityEngine.Signal("ouf:name","ouf:name@semantic://publication/1",
            GovernedIdentityEngine.ComparatorKind.CONCEPT,false,false,"assertion://name/1")),
        policy().sufficientRules());
    assertThat(candidates.retrieve(changedComparator,subject("ALPHA")).complete()).isFalse();
    var result=candidates.retrieve(policy(),subject("ALPHA"));
    assertThat(result.complete()).isTrue();
    assertThat(result.rows()).extracting(GovernedIdentityEngine.Candidate::objectId).containsExactly(matching);
    assertThat(new GovernedIdentityEngine().decide(policy(),subject("ALPHA"),result).outcome())
        .isEqualTo(GovernedIdentityEngine.Outcome.MATCH);
    db.sql("update ouf_udp.urban_object_current_state set updated_at=transaction_timestamp() where urban_object_id=:id")
        .param("id",matching).update();
    assertThat(candidates.retrieve(policy(),subject("ALPHA")).complete()).isFalse();
    assertThat(backfill.rebuild(policy()).indexedObjects()).isEqualTo(2);
    db.sql("insert into ouf_udp.urban_object(urban_object_id,tenant_id,canonical_type) values(gen_random_uuid(),'default','ouf:Road')").update();
    assertThat(candidates.retrieve(policy(),subject("ALPHA")).complete()).isFalse();
    assertThatThrownBy(()->backfill.rebuild(policy())).hasMessageContaining("UNMATERIALIZED_OBJECT");
    assertThat(candidates.retrieve(policy(),subject("ALPHA")).complete()).isFalse();
  }
  @Test void backfillRejectsDivergentCanonicalProjection(){
    clearObjects();
    UUID id=materialize("three","Alpha");
    db.sql("update ouf_udp.urban_object_current_state set canonical_payload=cast(:payload as jsonb) where urban_object_id=:id")
        .param("id",id).param("payload","{\"ouf:name\":\"Other\"}").update();
    assertThatThrownBy(()->backfill.rebuild(policy())).hasMessageContaining("CURRENT_VALUE_MISMATCH");
    assertThat(candidates.retrieve(policy(),subject("Alpha")).complete()).isFalse();
  }
  @Test void unionsIndexedSeedsFromEveryExposedField(){
    clearObjects();
    UUID first=materializeWithAddress("a","Alpha","Via Roma");
    UUID second=materializeWithAddress("b","Beta","Via Milano");
    var policy=new GovernedIdentityEngine.Policy("policy://identity/two","1","default","ouf:Road","registry",2,true,
        List.of(new GovernedIdentityEngine.Signal("ouf:name","ouf:name@semantic://publication/1",
                GovernedIdentityEngine.ComparatorKind.TEXT_V1,false,false,"assertion://name/1"),
            new GovernedIdentityEngine.Signal("ouf:address","ouf:address@semantic://publication/1",
                GovernedIdentityEngine.ComparatorKind.TEXT_V1,false,false,"assertion://address/1")),
        List.of(new GovernedIdentityEngine.SufficientRule("whole",Set.of("ouf:name","ouf:address"),"assertion://whole/1")));
    assertThat(backfill.rebuild(policy).indexedObjects()).isEqualTo(2);
    var subject=new GovernedIdentityEngine.Subject("default","ouf:Road","registry",Map.of(
        "ouf:name",new GovernedIdentityEngine.Value("ouf:name@semantic://publication/1","Beta","handoff://probe/name"),
        "ouf:address",new GovernedIdentityEngine.Value("ouf:address@semantic://publication/1","Via Roma","handoff://probe/address")));
    var result=candidates.retrieve(policy,subject);
    assertThat(result.complete()).isTrue();
    assertThat(result.rows()).extracting(GovernedIdentityEngine.Candidate::objectId).containsExactlyInAnyOrder(first,second);
    assertThat(new GovernedIdentityEngine().decide(policy,subject,result).outcome())
        .isEqualTo(GovernedIdentityEngine.Outcome.REVIEW_REQUIRED);
    var narrow=new GovernedIdentityEngine.Policy(policy.ref(),policy.version(),policy.tenantId(),
        policy.canonicalClass(),policy.sourceId(),1,true,policy.signals(),policy.sufficientRules());
    assertThat(new GovernedIdentityEngine().decide(narrow,subject,candidates.retrieve(narrow,subject)).outcome())
        .isEqualTo(GovernedIdentityEngine.Outcome.RESOLUTION_TOO_BROAD);
  }
  @Test void verifiedEmptyScopeCanCreateFirstObjectWithSubsetOfConfiguredFields(){
    clearObjects();
    var base=policy();
    var two=new GovernedIdentityEngine.Policy("policy://identity/empty","1","default","ouf:Road","registry",2,true,
        List.of(base.signals().getFirst(),new GovernedIdentityEngine.Signal("ouf:address",
            "ouf:address@semantic://publication/1",GovernedIdentityEngine.ComparatorKind.TEXT_V1,
            false,false,"assertion://address/1")),
        List.of(new GovernedIdentityEngine.SufficientRule("whole",Set.of("ouf:name","ouf:address"),"assertion://whole/1")));
    assertThat(backfill.rebuild(two).indexedObjects()).isZero();
    var result=candidates.retrieve(two,subject("Alpha"));
    assertThat(result.complete()).isTrue();
    assertThat(result.rows()).isEmpty();
    assertThat(new GovernedIdentityEngine().decide(two,subject("Alpha"),result).outcome())
        .isEqualTo(GovernedIdentityEngine.Outcome.NEW_OBJECT);
    db.sql("insert into ouf_udp.urban_object(urban_object_id,tenant_id,canonical_type) values(gen_random_uuid(),'default','ouf:Road')").update();
    assertThat(candidates.retrieve(two,subject("Alpha")).complete()).isFalse();
  }
  @Test void differentFieldShapesAreIndexedAsPotentiallyUncertainCandidates(){
    clearObjects();
    UUID smaller=materialize("small","Alpha");
    materializeWithAddress("large","Beta","Via Milano");
    var base=policy();
    var two=new GovernedIdentityEngine.Policy("policy://identity/heterogeneous","1","default","ouf:Road","registry",2,true,
        List.of(base.signals().getFirst(),new GovernedIdentityEngine.Signal("ouf:address",
            "ouf:address@semantic://publication/1",GovernedIdentityEngine.ComparatorKind.TEXT_V1,
            false,false,"assertion://address/1")),
        List.of(new GovernedIdentityEngine.SufficientRule("whole",Set.of("ouf:name","ouf:address"),"assertion://whole/1")));
    assertThat(backfill.rebuild(two).indexedObjects()).isEqualTo(2);
    var incoming=new GovernedIdentityEngine.Subject("default","ouf:Road","registry",Map.of(
        "ouf:name",new GovernedIdentityEngine.Value("ouf:name@semantic://publication/1","Alpha","handoff://probe/name"),
        "ouf:address",new GovernedIdentityEngine.Value("ouf:address@semantic://publication/1","Via Roma","handoff://probe/address")));
    var retrieved=candidates.retrieve(two,incoming);
    assertThat(retrieved.complete()).isTrue();
    assertThat(retrieved.rows()).extracting(GovernedIdentityEngine.Candidate::objectId).containsExactly(smaller);
    assertThat(new GovernedIdentityEngine().decide(two,incoming,retrieved).outcome())
        .isEqualTo(GovernedIdentityEngine.Outcome.MATCH);
    var allDifferent=new GovernedIdentityEngine.Subject("default","ouf:Road","registry",Map.of(
        "ouf:name",new GovernedIdentityEngine.Value("ouf:name@semantic://publication/1","Other","handoff://probe/name"),
        "ouf:address",new GovernedIdentityEngine.Value("ouf:address@semantic://publication/1","Via Nuova","handoff://probe/address")));
    assertThat(new GovernedIdentityEngine().decide(two,allDifferent,candidates.retrieve(two,allDifferent)).outcome())
        .isEqualTo(GovernedIdentityEngine.Outcome.REVIEW_REQUIRED);
  }
  private void clearObjects(){db.sql("truncate table ouf_udp.property_conflict,ouf_udp.property_value,ouf_udp.materialization_observation,ouf_udp.property_contribution,ouf_udp.object_revision,ouf_udp.resolution_issue,ouf_udp.resolution_decision,ouf_udp.source_binding,ouf_udp.urban_object,ouf_udp.materialization_job,ouf_udp.handoff_event,ouf_udp.handoff_intake restart identity cascade").update();}
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
  private UUID materializeWithAddress(String id,String name,String address){
    Map<String,Object> envelope=envelope(id,name);
    envelope.put("canonicalPayload",Map.of("code",id,"name",name,"address",address));
    intake.accept(envelope);
    UUID object=resolver.resolve("candidate-"+id,envelope,new UdpPorts.ResolutionProfile("CANONICAL_KEY","1","policy://resolution/1","ouf:Road","code","code")).targetUrbanObjectId();
    materializer.materialize("candidate-"+id,object,envelope,new UdpPorts.MaterializationProfile("policy://authority/1",
        List.of(new UdpPorts.PropertyRule("name","ouf:name","string","OPEN",List.of("registry")),
            new UdpPorts.PropertyRule("address","ouf:address","string","OPEN",List.of("registry")))));
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
