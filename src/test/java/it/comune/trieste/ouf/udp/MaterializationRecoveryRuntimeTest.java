package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.authorization.ResourceContext;
import it.comune.trieste.ouf.authorization.LocalAuthorization;
import it.comune.trieste.ouf.authorization.ServletAuthorization;
import it.comune.trieste.ouf.authorization.TrustedPrincipal;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties="ouf.udp.materialization-recovery.enabled=true")
@AutoConfigureMockMvc(addFilters=false)
class MaterializationRecoveryRuntimeTest {
  private static final String HASH="sha256:"+"a".repeat(64);
  private static final String CAP=MaterializationRecoveryService.CAPABILITY;
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",()->required("OUF_UDP_DB_URL"));
    r.add("spring.datasource.username",()->required("OUF_UDP_DB_USER"));
    r.add("spring.datasource.password",()->required("OUF_UDP_DB_PASSWORD"));
    r.add("ouf.udp.lake.required",()->"false");
  }
  @Autowired MaterializationRecoveryService recovery;
  @Autowired ResolutionRepository jobs;
  @Autowired MaterializationReferenceGate gate;
  @Autowired JdbcClient db;
  @Autowired ObjectMapper json;
  @Autowired MockMvc http;
  @MockBean HistoricalContractCatalog catalog;

  @BeforeEach void clean(){
    db.sql("truncate table ouf_udp.handoff_intake,ouf_udp.lake_object restart identity cascade").update();
    reset(catalog);when(catalog.resolve(anyMap())).thenReturn(ready(HASH));
  }
  @Test void scopedGrantAdmitsHttpReviewAndRetryWithoutGrantOnGenericCapability() throws Exception {
    UUID job=fixture("http-scoped");String original=payload(job);
    var engine=scopedPolicy(job,"operator","OPEN","roads");
    var principal=principal("operator",PrincipalContext.ActorType.HUMAN,Set.of(CAP));
    assertThat(engine.evaluate(principal,new ResourceContext("capability",null,"tenant",null,Map.of()),CAP,"COMMAND").allowed()).isFalse();
    var response=http.perform(get(api(job)).with(identity(engine,principal)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.jobId").value(job.toString()))
        .andExpect(jsonPath("$.retryEligible").value(true)).andReturn();
    assertThat(state(job)).isEqualTo("QUARANTINED");assertThat(events()).isZero();
    var review=json.readValue(response.getResponse().getContentAsByteArray(),MaterializationRecoveryService.Review.class);
    http.perform(post(api(job)+"/retry").with(identity(engine,principal)).contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsBytes(request(review,UUID.randomUUID()))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.acceptedVersion").value(3));
    assertThat(state(job)).isEqualTo("READY");assertThat(events()).isOne();assertThat(payload(job)).isEqualTo(original);
  }
  @Test void scopedHttpAdmissionNeverReleasesOtherJobsSubjectsLabelsOrSources() throws Exception {
    UUID job=fixture("http-denied"),outside=fixture("http-outside");
    var principal=principal("operator",PrincipalContext.ActorType.HUMAN,Set.of(CAP));
    var allowed=scopedPolicy(job,"operator","OPEN","roads");
    http.perform(get(api(outside)).with(identity(allowed,principal))).andExpect(status().isForbidden());
    for(var engine:List.of(scopedPolicy(job,"different-human","OPEN","roads"),
        scopedPolicy(job,"operator","RESTRICTED","roads"),scopedPolicy(job,"operator","OPEN","other-source"))){
      http.perform(get(api(job)).with(identity(engine,principal))).andExpect(status().isForbidden());
      http.perform(post(api(job)+"/retry").with(identity(engine,principal)).contentType(MediaType.APPLICATION_JSON)
          .content(json.writeValueAsBytes(new MaterializationRecoveryService.Request(UUID.randomUUID(),2L,HASH,"reviewed"))))
          .andExpect(status().isForbidden());
    }
    verifyNoInteractions(catalog);assertThat(events()).isZero();assertThat(state(job)).isEqualTo("QUARANTINED");
    assertThat(state(outside)).isEqualTo("QUARANTINED");
  }
  @Test void httpAdmissionRequiresHumanScopeAndRejectsUntrustedActorHeaders() throws Exception {
    UUID job=fixture("http-principal");var engine=scopedPolicy(job,"operator","OPEN","roads");
    for(var principal:List.of(principal("operator",PrincipalContext.ActorType.HUMAN,Set.of()),
        principal("operator",PrincipalContext.ActorType.SERVICE,Set.of(CAP)),
        principal("operator",PrincipalContext.ActorType.AI_AGENT,Set.of(CAP)))){
      http.perform(get(api(job)).with(identity(engine,principal))).andExpect(status().isForbidden());
    }
    http.perform(get(api(job))).andExpect(status().isForbidden());
    http.perform(get(api(job)).with(identity(engine,principal("operator",PrincipalContext.ActorType.HUMAN,Set.of(CAP))))
        .header("X-Actor-Type","HUMAN")).andExpect(status().isBadRequest());
    verifyNoInteractions(catalog);assertThat(events()).isZero();assertThat(state(job)).isEqualTo("QUARANTINED");
  }
  // Real SDK admission/resource enforcement. JWT signature and gateway filters have separate tests.
  private LocalAuthorization scopedPolicy(UUID job,String subject,String label,String source) throws Exception {
    var now=Instant.now();
    var constraints=new AuthorizationPolicy.GrantConstraints("ALLOW",null,"materialization-job",job.toString(),
        Map.of("module","UDP","sourceRef",source,"jobRef","run","typeRef","ROAD"),Set.of(label),Set.of(),null,Set.of(),null);
    var grant=new AuthorizationPolicy.Grant("http-job-grant",CAP,"tenant",subject,null,null,
        now.minusSeconds(60),now.plusSeconds(600),constraints);
    var descriptor=new AuthorizationPolicy.CapabilityDescriptor(CAP,"COMMAND",CAP,Set.of(PrincipalContext.ActorType.HUMAN));
    var bundle=new AuthorizationPolicy.PolicyBundle("http-recovery-policy",1,now,List.of(descriptor),List.of(grant));
    var engine=new LocalAuthorization(Clock.systemUTC(),Duration.ofMinutes(5));
    byte[] bytes=json.writeValueAsBytes(bundle);var path=Files.createTempFile("recovery-http-policy-",".json");
    try{
      Files.write(path,bytes);String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
      assertThat(engine.refresh(new LocalAuthorization.BundleReference(path,bundle.bundleId(),1,hash,1)).installed()).isTrue();
    }finally{Files.deleteIfExists(path);}
    return engine;
  }
  private static PrincipalContext principal(String subject,PrincipalContext.ActorType actor,Set<String> scopes){
    return new PrincipalContext(subject,"tenant",actor,null,"http-auth","issuer","audience",scopes);
  }
  private static RequestPostProcessor identity(LocalAuthorization engine,PrincipalContext principal){
    return request->{request.getServletContext().setAttribute(ServletAuthorization.RUNTIME,engine);
      request.setUserPrincipal(new TrustedPrincipal(principal));return request;};
  }
  private static String api(UUID job){return "/api/udp/v1/governance/materialization/jobs/"+job;}
  @Test void reviewIsReadOnlyAndRetryPreservesOriginalDurableInputAndCounters() throws Exception {
    UUID job=fixture("one");String payload=payload(job);
    var review=recovery.review(job,human(),scope->{
      assertThat(scope.organizationId()).isNull();
      assertThat(scope.attributes()).containsEntry("dataAccessLabel","OPEN")
          .containsEntry("sourceRef","roads").containsEntry("jobRef","run");
    });
    assertThat(review.contractReady()).isTrue();assertThat(review.retryEligible()).isTrue();
    assertThat(events()).isZero();assertThat(state(job)).isEqualTo("QUARANTINED");
    var request=request(review,UUID.randomUUID());var receipt=recovery.retry(job,request,human(),scope->{});
    assertThat(receipt.acceptedVersion()).isEqualTo(3);assertThat(receipt.repeated()).isFalse();
    assertThat(state(job)).isEqualTo("READY");assertThat(payload(job)).isEqualTo(payload);
    var counts=db.sql("select attempts,integrity_attempts,integrity_baseline_hash from ouf_udp.materialization_job where job_id=:id")
        .param("id",job).query().singleRow();
    assertThat(counts).containsEntry("attempts",1).containsEntry("integrity_attempts",1)
        .containsEntry("integrity_baseline_hash",null);
    assertThat(db.sql("select count(*) from ouf_udp.handoff_intake").query(Long.class).single()).isOne();
    assertThat(db.sql("select count(*) from ouf_udp.replay_plan").query(Long.class).single()).isZero();
    assertThat(events()).isOne();
    var detail=json.readTree(db.sql("select safe_detail::text from ouf_udp.handoff_event where event_id=:id")
        .param("id",request.operationId()).query(String.class).single());
    assertThat(detail.path("actorType").asText()).isEqualTo("HUMAN");
    assertThat(detail.path("authorizationDecisionRef").asText()).isEqualTo("auth-test");
    assertThat(detail.path("previousSafeFailureCode").asText()).isEqualTo("UDP_REFERENCE_INTEGRITY_CONTRACT_INVALID");
    var claim=jobs.claim("worker",java.time.Duration.ofSeconds(30)).orElseThrow();
    assertThat(gate.verify(claim)).isTrue();jobs.complete(claim);
    assertThat(state(job)).isEqualTo("SUCCEEDED");
    assertThat(db.sql("select state from ouf_udp.handoff_intake where handoff_id='one'").query(String.class).single())
        .isEqualTo("PROCESSED");
  }
  @Test void sdkDataLabelConstraintsProtectBothReviewAndRetryBeforeReferenceRead(){
    UUID job=fixture("label");
    var allowed=labelAuthorization(job,"OPEN");
    var review=recovery.review(job,human(),allowed);
    reset(catalog);
    var denied=labelAuthorization(job,"RESTRICTED");
    assertThatThrownBy(()->recovery.review(job,human(),denied)).isInstanceOf(SecurityException.class);
    assertThatThrownBy(()->recovery.retry(job,request(review,UUID.randomUUID()),human(),denied))
        .isInstanceOf(SecurityException.class);
    verifyNoInteractions(catalog);
    assertThat(events()).isZero();assertThat(state(job)).isEqualTo("QUARANTINED");
    when(catalog.resolve(anyMap())).thenReturn(ready(HASH));
    recovery.retry(job,request(review,UUID.randomUUID()),human(),allowed);
    assertThat(state(job)).isEqualTo("READY");assertThat(events()).isOne();
  }
  private static java.util.function.Consumer<ResourceContext> labelAuthorization(UUID job,String label){
    var now=Instant.parse("2026-10-01T10:00:00Z");
    var principal=new PrincipalContext("operator","tenant",PrincipalContext.ActorType.HUMAN,
        null,"auth-test","issuer","audience",Set.of(CAP));
    var constraints=new AuthorizationPolicy.GrantConstraints("ALLOW",null,"materialization-job",job.toString(),
        Map.of("module","UDP","sourceRef","roads","jobRef","run"),Set.of(label),Set.of(),null,Set.of(),null);
    var grant=new AuthorizationPolicy.Grant("review-grant",CAP,"tenant","operator",null,null,
        now.minusSeconds(60),now.plusSeconds(600),constraints);
    var descriptor=new AuthorizationPolicy.CapabilityDescriptor(CAP,"COMMAND",CAP,Set.of(PrincipalContext.ActorType.HUMAN));
    var policy=new AuthorizationPolicy.PolicyBundle("review-policy",1,now,List.of(descriptor),List.of(grant));
    return resource->{
      if(!AuthorizationPolicy.evaluate(policy,principal,resource,CAP,"COMMAND",now).allowed())
        throw new SecurityException("data label denied");
    };
  }
  @Test void sameOperationIsIdempotentEvenAfterWorkerClaim(){
    UUID job=fixture("idempotent");var request=request(recovery.review(job,human(),s->{}),UUID.randomUUID());
    recovery.retry(job,request,human(),s->{});jobs.claim("worker",java.time.Duration.ofSeconds(30)).orElseThrow();
    var repeated=recovery.retry(job,request,human(),s->{});
    assertThat(repeated.repeated()).isTrue();assertThat(repeated.state()).isEqualTo("RUNNING");
    assertThat(repeated.acceptedVersion()).isEqualTo(3);assertThat(events()).isOne();
  }
  @Test void staleVersionsSnapshotsAndChangedIdempotencyRequestsNeverRequeue(){
    UUID job=fixture("stale");var view=recovery.review(job,human(),s->{});
    var operation=UUID.randomUUID();
    var stale=new MaterializationRecoveryService.Request(operation,1L,view.snapshotHash(),"reviewed");
    assertConflict(()->recovery.retry(job,stale,human(),s->{}));assertThat(events()).isZero();
    when(catalog.resolve(anyMap())).thenReturn(ready("sha256:"+"b".repeat(64)));
    assertConflict(()->recovery.retry(job,request(view,operation),human(),s->{}));
    assertThat(state(job)).isEqualTo("QUARANTINED");
    var fresh=recovery.review(job,human(),s->{});var accepted=request(fresh,operation);
    recovery.retry(job,accepted,human(),s->{});
    assertConflict(()->recovery.retry(job,new MaterializationRecoveryService.Request(operation,
        accepted.expectedVersion(),accepted.expectedSnapshotHash(),"different reason"),human(),s->{}));
    assertThat(events()).isOne();
  }
  @Test void tenantHumanCapabilityAndResourceDenialsFailBeforeReferenceRead(){
    UUID job=fixture("auth");reset(catalog);
    var service=new TrustedHumanContext("SERVICE","service","tenant",Set.of(CAP),"auth","corr");
    var noScope=new TrustedHumanContext("HUMAN","operator","tenant",Set.of(),"auth","corr");
    assertThatThrownBy(()->recovery.review(job,service,s->{})).isInstanceOf(SecurityException.class);
    assertThatThrownBy(()->recovery.review(job,noScope,s->{})).isInstanceOf(SecurityException.class);
    var other=new TrustedHumanContext("HUMAN","operator","other",Set.of(CAP),"auth","corr");
    assertThatThrownBy(()->recovery.review(job,other,s->{throw new AssertionError("must not release other tenant");}))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(()->recovery.review(job,human(),s->{throw new SecurityException("source denied");}))
        .isInstanceOf(SecurityException.class);
    verifyNoInteractions(catalog);assertThat(events()).isZero();assertThat(state(job)).isEqualTo("QUARANTINED");
  }
  @Test void missingInvalidOrDriftingContractsDoNotBypassTheGate(){
    UUID job=fixture("refs");
    when(catalog.resolve(anyMap())).thenReturn(new HistoricalContractCatalog.Resolution(List.of(),List.of(HASH),null));
    var missing=recovery.review(job,human(),s->{});
    assertThat(missing.contractReady()).isFalse();assertConflict(()->recovery.retry(job,request(missing,UUID.randomUUID()),human(),s->{}));
    when(catalog.resolve(anyMap())).thenThrow(new IllegalArgumentException("private profile body"));
    var invalid=recovery.review(job,human(),s->{});assertThat(invalid.contractCheck()).isEqualTo("CONTRACT_INVALID");
    assertThat(invalid.toString()).doesNotContain("private profile body");
    doReturn(ready(HASH)).when(catalog).resolve(anyMap());
    db.sql("update ouf_udp.materialization_job set integrity_baseline_hash=:hash where job_id=:id")
        .param("hash","sha256:"+"c".repeat(64)).param("id",job).update();
    var drift=recovery.review(job,human(),s->{});
    assertConflict(()->recovery.retry(job,request(drift,UUID.randomUUID()),human(),s->{}));
    assertThat(state(job)).isEqualTo("QUARANTINED");assertThat(events()).isZero();
  }
  @Test void humanResolutionQuarantineAndSucceededJobsAreExcluded(){
    UUID job=fixture("exclude");
    db.sql("update ouf_udp.materialization_job set safe_failure_code='UDP_RESOLUTION_REVIEW_REQUIRED' where job_id=:id")
        .param("id",job).update();
    var review=recovery.review(job,human(),s->{});assertThat(review.retryEligible()).isFalse();
    assertConflict(()->recovery.retry(job,request(review,UUID.randomUUID()),human(),s->{}));
    db.sql("update ouf_udp.materialization_job set state='SUCCEEDED' where job_id=:id").param("id",job).update();
    assertThat(recovery.review(job,human(),s->{}).retryEligible()).isFalse();assertThat(events()).isZero();
  }
  @Test void canonicalDecisionPreventsTechnicalRetry(){
    UUID job=fixture("decision");
    db.sql("insert into ouf_udp.resolution_decision(resolution_decision_id,handoff_id,candidate_ref,outcome,"
        +"strategy_id,strategy_version,evidence_refs,decided_by,policy_ref) "
        +"values(gen_random_uuid(),'decision','candidate','NEW_OBJECT','CANONICAL_KEY','1','[]','worker','policy')").update();
    var review=recovery.review(job,human(),s->{});assertThat(review.retryEligible()).isFalse();
    assertConflict(()->recovery.retry(job,request(review,UUID.randomUUID()),human(),s->{}));
    assertThat(events()).isZero();
  }
  @Test void concurrentDistinctConfirmationsCommitOnlyOneRetry() throws Exception {
    UUID job=fixture("concurrent");var review=recovery.review(job,human(),s->{});
    var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
    try{
      Callable<Boolean> attempt=()->{start.await();try{recovery.retry(job,request(review,UUID.randomUUID()),human(),s->{});return true;}
        catch(ResponseStatusException conflict){assertThat(conflict.getStatusCode().value()).isEqualTo(409);return false;}};
      var a=pool.submit(attempt);var b=pool.submit(attempt);start.countDown();
      assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
      assertThat(events()).isOne();assertThat(state(job)).isEqualTo("READY");
    }finally{pool.shutdownNow();}
  }
  @Test void auditFailureRollsBackJobTransition(){
    UUID job=fixture("rollback");var review=recovery.review(job,human(),s->{});
    db.sql("create function ouf_udp.test_reject_retry_event() returns trigger language plpgsql as $$ "
        +"begin if new.event_type='MATERIALIZATION_RETRY_AUTHORIZED' then raise exception 'TEST_AUDIT_FAILURE'; end if; return new; end $$").update();
    db.sql("create trigger test_reject_retry_event before insert on ouf_udp.handoff_event "
        +"for each row execute function ouf_udp.test_reject_retry_event()").update();
    try{
      assertThatThrownBy(()->recovery.retry(job,request(review,UUID.randomUUID()),human(),s->{})).isInstanceOf(RuntimeException.class);
      assertThat(state(job)).isEqualTo("QUARANTINED");assertThat(events()).isZero();
    }finally{db.sql("drop trigger test_reject_retry_event on ouf_udp.handoff_event").update();db.sql("drop function ouf_udp.test_reject_retry_event()").update();}
  }
  private UUID fixture(String handoff){
    UUID raw=UUID.randomUUID(),job=UUID.randomUUID();
    db.sql("insert into ouf_udp.lake_object(lake_object_id,tenant_id,source_id,type_code,tier,content_hash,media_type,"
        +"logical_size_bytes,locator,state,retention_class,access_label,retention_until) "
        +"values(:id,'tenant','roads','ROAD','RAW',:hash,'application/json',100,'private-locator','VERIFIED','AUDIT','OPEN',transaction_timestamp()+interval '1 day')")
        .param("id",raw).param("hash","sha256:"+raw.toString().replace("-","").repeat(2)).update();
    db.sql("insert into ouf_udp.handoff_intake(handoff_id,ingestion_run_id,ingestion_id,source_id,type_code,source_object_id,"
        +"content_hash,payload_json,receipt_ref,raw_lake_object_id) values(:h,'run','ingestion','roads','ROAD',:h,:hash,"
        +"'{\"contractRefs\":{\"bundleRef\":\"bundle\",\"sourceSchemaRef\":\"schema\",\"semanticPublicationSetRef\":\"semantic\",\"adapterProfileRef\":\"adapter\"}}',:receipt,:raw)")
        .param("h",handoff).param("hash",HASH).param("receipt","udp://"+handoff).param("raw",raw).update();
    db.sql("insert into ouf_udp.materialization_job(job_id,handoff_id,state,state_version,attempts,integrity_attempts,safe_failure_code) "
        +"values(:id,:handoff,'QUARANTINED',2,1,1,'UDP_REFERENCE_INTEGRITY_CONTRACT_INVALID')")
        .param("id",job).param("handoff",handoff).update();return job;
  }
  private String payload(UUID job){return db.sql("select h.payload_json::text from ouf_udp.handoff_intake h "
      +"join ouf_udp.materialization_job j on j.handoff_id=h.handoff_id where j.job_id=:id").param("id",job).query(String.class).single();}
  private String state(UUID job){return db.sql("select state from ouf_udp.materialization_job where job_id=:id")
      .param("id",job).query(String.class).single();}
  private long events(){return db.sql("select count(*) from ouf_udp.handoff_event where event_type='MATERIALIZATION_RETRY_AUTHORIZED'")
      .query(Long.class).single();}
  private static TrustedHumanContext human(){return new TrustedHumanContext("HUMAN","operator","tenant",Set.of(CAP),"auth-test","corr-test");}
  private static MaterializationRecoveryService.Request request(MaterializationRecoveryService.Review review,UUID operation){
    return new MaterializationRecoveryService.Request(operation,review.stateVersion(),review.snapshotHash(),"Reviewed technical quarantine");}
  private static HistoricalContractCatalog.Resolution ready(String hash){return new HistoricalContractCatalog.Resolution(List.of(),List.of(),hash);}
  private static void assertConflict(Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(
      ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));}
  private static String required(String name){String value=System.getenv(name);if(value==null)throw new IllegalStateException(name+" required");return value;}
}
