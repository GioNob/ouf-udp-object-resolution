package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
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
  @Autowired HandoffIntakeService intake;
  @Autowired ObjectResolutionService resolver;
  @Autowired CanonicalMaterializer materializer;
  @Autowired ScopedIdentityCandidateRepository candidates;
  @Autowired GovernedIdentityReviewRepository reviews;
  @Autowired GovernedIdentityScopeLock scopeLock;
  @Autowired GovernedIdentityResolutionService governed;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
  @Autowired JdbcClient db;

  private final UdpPorts.ResolutionProfile legacy=new UdpPorts.ResolutionProfile("CANONICAL_KEY","1","policy://resolution/1","ouf:Road","code","code");
  private final UdpPorts.MaterializationProfile authority=new UdpPorts.MaterializationProfile("policy://authority/1",
      List.of(new UdpPorts.PropertyRule("name","ouf:name","string","OPEN",List.of("registry"))));

  @BeforeEach void clean(){db.sql("truncate table ouf_udp.property_conflict,ouf_udp.property_value,ouf_udp.materialization_observation,ouf_udp.property_contribution,ouf_udp.object_revision,ouf_udp.resolution_issue,ouf_udp.resolution_decision,ouf_udp.source_binding,ouf_udp.urban_object,ouf_udp.materialization_job,ouf_udp.handoff_event,ouf_udp.handoff_intake restart identity cascade").update();}

  @Test void readsCurrentPublishedValuesAcrossEntireTenantClassAndBoundsOverflow(){
    UUID first=materialize("one","Alpha"),second=materialize("two","Beta");
    var full=candidates.retrieve(policy(2));
    assertThat(full.complete()).isTrue();
    assertThat(full.coverageRef()).startsWith("postgres-snapshot://");
    assertThat(full.rows()).extracting(GovernedIdentityEngine.Candidate::objectId).containsExactlyInAnyOrder(first,second);
    assertThat(full.rows().stream().filter(candidate->candidate.objectId().equals(first)).findFirst().orElseThrow().values().get("ouf:name"))
        .satisfies(value->{assertThat(value.semanticRef()).isEqualTo("ouf:name@semantic://publication/1");
          assertThat(value.raw()).isEqualTo("Alpha");assertThat(value.provenanceRef()).startsWith("contribution://");});
    var decision=new GovernedIdentityEngine().decide(policy(2),
        new GovernedIdentityEngine.Subject("default","ouf:Road","registry",Map.of("ouf:name",
            new GovernedIdentityEngine.Value("ouf:name@semantic://publication/1","Alpha","handoff://probe"))),full);
    assertThat(decision.outcome()).isEqualTo(GovernedIdentityEngine.Outcome.MATCH);
    assertThat(decision.assessments()).hasSize(2);
    var bounded=candidates.retrieve(policy(1));
    assertThat(bounded.complete()).isFalse();
    assertThat(new GovernedIdentityEngine().decide(policy(1),
        new GovernedIdentityEngine.Subject("default","ouf:Road","registry",Map.of()),bounded).outcome())
        .isEqualTo(GovernedIdentityEngine.Outcome.RESOLUTION_TOO_BROAD);
    assertThat(candidates.retrieve(new GovernedIdentityEngine.Policy("policy://identity/1","1","other","ouf:Road","registry",2,false,
        policy(2).signals(),policy(2).sufficientRules())).rows()).isEmpty();
  }

  @Test void persistsGovernedReviewEvidenceAndNeverOffersPartialCoverageForApproval(){
    UUID first=materialize("one","Alpha");materialize("two","Beta");
    var subject=new GovernedIdentityEngine.Subject("default","ouf:Road","registry",Map.of("ouf:name",
        new GovernedIdentityEngine.Value("ouf:name@semantic://publication/1","Gamma","handoff://probe")));
    var engine=new GovernedIdentityEngine();
    var full=candidates.retrieve(policy(2));
    var review=engine.decide(policy(2),subject,full);
    intake.accept(envelope("review","Gamma"));
    UUID decisionId=reviews.record("candidate-review",review,full);
    assertThat(reviews.record("candidate-review",review,full)).isEqualTo(decisionId);
    String evidence=db.sql("select evidence_refs::text from ouf_udp.resolution_decision where resolution_decision_id=:id")
        .param("id",decisionId).query(String.class).single();
    assertThat(evidence).contains("postgres-snapshot://","assertion://name/1","contribution://")
        .doesNotContain("Alpha","Beta");
    String selectable=db.sql("select candidate_refs::text from ouf_udp.resolution_issue where resolution_decision_id=:id")
        .param("id",decisionId).query(String.class).single();
    assertThat(selectable).contains(first.toString());

    var bounded=candidates.retrieve(policy(1));
    intake.accept(envelope("overflow","Delta"));
    UUID broadId=reviews.record("candidate-overflow",engine.decide(policy(1),subject,bounded),bounded);
    var issue=db.sql("select reason_code,candidate_refs::text candidate_refs from ouf_udp.resolution_issue where resolution_decision_id=:id")
        .param("id",broadId).query().singleRow();
    assertThat(issue.get("reason_code")).isEqualTo("UDP_RESOLUTION_TOO_BROAD");
    assertThat(issue.get("candidate_refs")).isEqualTo("[]");
  }

  @Test void objectWritesAndCandidateReadsUseTheSameTransactionScopeLock()throws Exception{
    assertThatThrownBy(()->scopeLock.acquire("default","ouf:Road"))
        .hasMessage("UDP_IDENTITY_TRANSACTION_REQUIRED");
    ExecutorService worker=Executors.newSingleThreadExecutor();
    try{
      new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status->{
        scopeLock.acquire("default","ouf:Road");
        assertThat(candidates.retrieve(policy(2)).complete()).isTrue();
        assertOtherConnectionCannotLock(worker);
      });
      new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status->{
        db.sql("insert into ouf_udp.urban_object(urban_object_id,tenant_id,canonical_type) values(gen_random_uuid(),'default','ouf:Road')").update();
        assertOtherConnectionCannotLock(worker);
      });
      assertThat(worker.submit(()->db.sql("select pg_try_advisory_xact_lock(hashtextextended(:key,0))")
          .param("key","identity:default:ouf:Road").query(Boolean.class).single()).get(3,TimeUnit.SECONDS))
          .isTrue();
    }finally{worker.shutdownNow();}
  }
  @Test void atomicMatchAndNewObjectRetainSourceBindingAcrossRetries(){
    Map<String,Object> fresh=envelope("fresh","Gamma");intake.accept(fresh);
    var created=governed.resolve("candidate-fresh",fresh,authority,autoPolicy());
    assertThat(created.outcome()).isEqualTo("NEW_OBJECT");
    UUID existing=materialize("one","Alpha");
    assertThat(created.targetUrbanObjectId()).isNotEqualTo(existing);
    assertThat(governed.resolve("candidate-fresh",fresh,authority,autoPolicy()).targetUrbanObjectId())
        .isEqualTo(created.targetUrbanObjectId());
    Map<String,Object> match=envelope("match","Alpha");intake.accept(match);
    var matched=governed.resolve("candidate-match",match,authority,policy(2));
    assertThat(matched.outcome()).isEqualTo("MATCH");
    assertThat(matched.targetUrbanObjectId()).isEqualTo(existing);
    assertThat(governed.resolve("candidate-match",match,authority,policy(2)).duplicate()).isTrue();
    assertThat(db.sql("select count(*) from ouf_udp.urban_object where canonical_type='ouf:Road'")
        .query(Long.class).single()).isEqualTo(2);
    assertThat(db.sql("select urban_object_id from ouf_udp.source_binding where source_object_id='fresh'")
        .query(UUID.class).single()).isEqualTo(created.targetUrbanObjectId());
    String evidence=db.sql("select evidence_refs::text from ouf_udp.resolution_decision where handoff_id='candidate-fresh'")
        .query(String.class).single();
    assertThat(evidence).contains("postgres-snapshot://","SOURCE_SCOPED_CREATION").doesNotContain("Gamma");
  }

  @Test void conflictingExistingSourceBindingQuarantinesWithoutReassignment(){
    UUID existing=materialize("one","Alpha");
    Map<String,Object> conflicting=envelope("one","Different");
    conflicting.put("handoffId","candidate-conflict");
    intake.accept(conflicting);
    var result=governed.resolve("candidate-conflict",conflicting,authority,autoPolicy());
    assertThat(result.outcome()).isEqualTo("REVIEW_REQUIRED");
    assertThat(db.sql("select urban_object_id from ouf_udp.source_binding where source_object_id='one'")
        .query(UUID.class).single()).isEqualTo(existing);
    var issue=db.sql("select reason_code,candidate_refs::text candidate_refs from ouf_udp.resolution_issue where handoff_id='candidate-conflict'")
        .query().singleRow();
    assertThat(issue.get("reason_code")).isEqualTo("UNRESOLVED_IDENTITY");
    assertThat(issue.get("candidate_refs")).contains(existing.toString());
  }
  private void assertOtherConnectionCannotLock(ExecutorService worker){
    Future<Boolean> other=worker.submit(()->db.sql("select pg_try_advisory_xact_lock(hashtextextended(:key,0))")
        .param("key","identity:default:ouf:Road").query(Boolean.class).single());
    try{assertThat(other.get(3,TimeUnit.SECONDS)).isFalse();}
    catch(Exception failure){throw new IllegalStateException(failure);}
  }

  private UUID materialize(String id,String name){
    Map<String,Object> envelope=envelope(id,name);
    intake.accept(envelope);
    UUID object=resolver.resolve("candidate-"+id,envelope,legacy).targetUrbanObjectId();
    materializer.materialize("candidate-"+id,object,envelope,authority);
    return object;
  }
  private Map<String,Object> envelope(String id,String name){return new LinkedHashMap<>(Map.ofEntries(
        Map.entry("handoffId","candidate-"+id),Map.entry("ingestionRunId","run-1"),Map.entry("ingestionId","ing-"+id),
        Map.entry("sourceIdentity",Map.of("sourceId","registry","typeCode","ROAD","sourceObjectId",id,"observedAt","2026-09-12T00:00:00Z")),
        Map.entry("operation","UPSERT"),Map.entry("canonicalPayload",Map.of("code",id,"name",name)),
        Map.entry("rawObjectRef","raw://"+id),Map.entry("contractRefs",Map.of("sourceSchemaRef","schema://road/1","bundleRef","bundle://road/1","semanticPublicationSetRef","semantic://publication/1","adapterProfileRef","adapter://rest/1")),
        Map.entry("lineageId","lineage-"+id),Map.entry("contentHash","sha256:"+id),
        Map.entry("acquiredAt","2026-09-12T00:00:00Z"),Map.entry("changeRepresentation",Map.of("mode","FULL_SNAPSHOT"))));}
  private GovernedIdentityEngine.Policy policy(int limit){return new GovernedIdentityEngine.Policy("policy://identity/1","1","default","ouf:Road","registry",limit,false,
      List.of(new GovernedIdentityEngine.Signal("ouf:name","ouf:name@semantic://publication/1",GovernedIdentityEngine.ComparatorKind.TEXT_V1,false,false,"assertion://name/1")),
      List.of(new GovernedIdentityEngine.SufficientRule("name",Set.of("ouf:name"),"assertion://rule/1")));}
  private GovernedIdentityEngine.Policy autoPolicy(){return new GovernedIdentityEngine.Policy("policy://identity/auto","1","default","ouf:Road","registry",2,true,
      List.of(new GovernedIdentityEngine.Signal("ouf:name","ouf:name@semantic://publication/1",GovernedIdentityEngine.ComparatorKind.TEXT_V1,false,false,"assertion://name/1")),
      List.of(new GovernedIdentityEngine.SufficientRule("name",Set.of("ouf:name"),"assertion://rule/1")));}
  private static String required(String n){String value=System.getenv(n);if(value==null)throw new IllegalStateException(n+" required");return value;}
}
