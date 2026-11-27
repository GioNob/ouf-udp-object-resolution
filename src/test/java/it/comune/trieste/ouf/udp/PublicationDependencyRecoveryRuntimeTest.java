package it.comune.trieste.ouf.udp;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class PublicationDependencyRecoveryRuntimeTest {
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",()->System.getenv("OUF_UDP_DB_URL"));
  r.add("spring.datasource.username",()->System.getenv("OUF_UDP_DB_USER"));
  r.add("spring.datasource.password",()->System.getenv("OUF_UDP_DB_PASSWORD"));
  r.add("ouf.udp.lake.required",()->"false");
  r.add("ouf.udp.publication-recovery.max-attempts",()->"3");
 }
 @Autowired HandoffIntakeService intake;@Autowired ResolutionRepository jobs;
 @Autowired MaterializationReferenceGate gate;@Autowired PublicationDependencyRecovery recovery;
 @Autowired ObjectResolutionService objectResolution;
 @Autowired CanonicalMaterializer canonicalMaterializer;
 @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
 @Autowired JdbcClient db;@MockitoBean HistoricalContractCatalog catalog;
 @BeforeEach void clean(){
  db.sql("truncate table ouf_udp.handoff_intake restart identity cascade").update();reset(catalog);
 }
 @Test void transientReadSurvivesNewWorkerAndRecoversWithoutDiscardingCumulativeHistory(){
  accept("transient");when(catalog.resolve(anyMap())).thenThrow(PublicationAccessFailure.network());
  var first=claim("worker-a");assertThat(gate.verify(first)).isFalse();
  assertThat(state()).isEqualTo("PAUSED");assertThat(attempts()).isEqualTo(1);
  assertThat(jobs.claim("early-worker",Duration.ofMinutes(2))).isEmpty();
  due();var second=claim("worker-after-restart");
  doReturn(ready()).when(catalog).resolve(anyMap());
  assertThat(gate.verify(second)).isTrue();gate.profilesReady(second);
  assertThat(attempts()).isZero();
  assertThat(db.sql("select dependency_cumulative_attempts from ouf_udp.materialization_job").query(Long.class).single()).isEqualTo(2);
  assertThat(db.sql("select count(*) from ouf_udp.handoff_event where event_type='PUBLICATION_DEPENDENCY_RECOVERED'").query(Long.class).single()).isEqualTo(1);
 }
 @Test void exhaustionPersistsAndDoesNotReleaseOnAnotherWorkerOrTimer(){
  accept("exhausted");when(catalog.resolve(anyMap())).thenThrow(PublicationAccessFailure.network());
  for(int n=1;n<=3;n++){assertThat(gate.verify(claim("worker-"+n))).isFalse();if(n<3)due();}
  assertThat(state()).isEqualTo("QUARANTINED");assertThat(attempts()).isEqualTo(3);
  assertThat(jobs.claim("new-worker",Duration.ofMinutes(2))).isEmpty();
  verify(catalog,times(3)).resolve(anyMap());
 }
 @Test void deadlineIsCheckedBeforeAnyNewNetworkRead(){
  accept("deadline");when(catalog.resolve(anyMap())).thenThrow(PublicationAccessFailure.network());
  gate.verify(claim("first"));
  db.sql("update ouf_udp.materialization_job set dependency_started_at=transaction_timestamp()-interval '25 hours',next_integrity_check_at=transaction_timestamp()-interval '1 second'").update();
  assertThat(gate.verify(claim("late"))).isFalse();assertThat(state()).isEqualTo("QUARANTINED");
  verify(catalog,times(1)).resolve(anyMap());
 }
 @Test void permanentDenialNeverEntersAutomaticRetry(){
  accept("denied");when(catalog.resolve(anyMap())).thenThrow(PublicationAccessFailure.status(403,null,Instant.now()));
  assertThat(gate.verify(claim("worker"))).isFalse();assertThat(state()).isEqualTo("QUARANTINED");
  assertThat(attempts()).isZero();
  assertThat(db.sql("select safe_failure_code from ouf_udp.materialization_job").query(String.class).single()).isEqualTo("UDP_PUBLICATION_ACCESS_DENIED");
 }
 @Test void retryAfterBeyondBudgetStopsInsteadOfRetryingEarly(){
  accept("server-delay");when(catalog.resolve(anyMap())).thenThrow(PublicationAccessFailure.status(429,"90000",Instant.now()));
  assertThat(gate.verify(claim("worker"))).isFalse();assertThat(state()).isEqualTo("QUARANTINED");
  assertThat(jobs.claim("new",Duration.ofMinutes(2))).isEmpty();
 }
 @Test void reclaimedLeaseRejectsOldClaimEvenWithSameWorkerName(){
  accept("fence");var old=claim("same-worker");
  db.sql("update ouf_udp.materialization_job set lease_until=transaction_timestamp()-interval '1 second'").update();
  var current=claim("same-worker");
  assertThatThrownBy(()->recovery.defer(old,PublicationAccessFailure.network())).hasMessage("UDP_RESOLUTION_LEASE_LOST");
  recovery.defer(current,PublicationAccessFailure.network());assertThat(attempts()).isEqualTo(1);
 }
 @Test void expiredWorkerCannotFailTheReplacementIntakeOrEmitAnEvent(){
  accept("late");var old=claim("worker");
  db.sql("update ouf_udp.materialization_job set lease_until=clock_timestamp()-interval '1 second'").update();
  jobs.fail(old,"UDP_LATE");
  var replacement=claim("worker");
  assertThatThrownBy(()->jobs.complete(old)).hasMessage("UDP_RESOLUTION_LEASE_LOST");
  jobs.fail(old,"UDP_LATE");
  assertThat(state()).isEqualTo("RUNNING");
  jobs.complete(replacement);jobs.fail(old,"UDP_LATE");
  assertThat(state()).isEqualTo("SUCCEEDED");
  assertThat(db.sql("select state from ouf_udp.handoff_intake").query(String.class).single()).isEqualTo("PROCESSED");
  assertThat(db.sql("select count(*) from ouf_udp.handoff_event where event_type='RESOLUTION_FAILED'").query(Long.class).single()).isZero();
 }
 @Test void rateLimitedSecondBatchResumesOnlyAfterPersistedAdmissionAndRechecksAllPins(
     @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
  try(var peer=new ReferenceBatchFixture(temp,1024,"resume",0)){
   accept("resume",peer);
   for(int status:new int[]{429,503}){
    peer.transientAt=1000;peer.transientStatus=status;
    var client=peer.client();
    doAnswer(call->client.resolveContracts(call.getArgument(0))).when(catalog).resolve(anyMap());
    var attempt=claim("worker-"+status);
    assertThat(gate.verify(attempt)).isFalse();
    assertThat(state()).isEqualTo("PAUSED");
    assertThat(db.sql("select next_integrity_check_at>=transaction_timestamp()+interval '1 second' from ouf_udp.materialization_job")
        .query(Boolean.class).single()).isTrue();
    assertThat(jobs.claim("too-early",Duration.ofMinutes(2))).isEmpty();
    due(); // Move the persisted due time; the replacement has no in-memory admission history.
   }
   var fresh=peer.client();
   doAnswer(call->fresh.resolveContracts(call.getArgument(0))).when(catalog).resolve(anyMap());
   var loop=new PublishedResolutionLoop(jobs,fresh,gate,objectResolution,canonicalMaterializer,null,db,transactions);
   loop.tick();
   assertThat(state()).isEqualTo("SUCCEEDED");
   assertThat(accept("resume",peer).duplicate()).isTrue();
   loop.tick();
   assertThat(db.sql("select count(*) from ouf_udp.materialization_observation where handoff_id='resume'").query(Long.class).single()).isOne();
   assertThat(db.sql("select count(*) from ouf_udp.property_contribution where handoff_id='resume'").query(Long.class).single()).isEqualTo(1024);
   assertThat(peer.starts).containsExactly(0,1000,0,1000,0,1000);
   assertThat(attempts()).isZero();
   assertThat(db.sql("select dependency_cumulative_attempts from ouf_udp.materialization_job").query(Long.class).single()).isEqualTo(3);
  }
 }
 @Test void oneMissingPinQuarantinesTheEntireHandoffWithExactSafeEvidence(
     @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
  try(var peer=new ReferenceBatchFixture(temp,1024,"missing",0)){
   accept("missing-pin",peer);
   var client=peer.client();
   doAnswer(call->client.resolveContracts(call.getArgument(0))).when(catalog).resolve(anyMap());
   peer.transientAt=1000;
   assertThat(gate.verify(claim("pre-revocation"))).isFalse();
   assertThat(state()).isEqualTo("PAUSED");
   due();
   var replacement=peer.client();
   doAnswer(call->replacement.resolveContracts(call.getArgument(0))).when(catalog).resolve(anyMap());
   peer.missing=1007;
   var materializer=mock(CanonicalMaterializer.class);
   var resolver=mock(ObjectResolutionService.class);
   new PublishedResolutionLoop(jobs,replacement,gate,resolver,materializer,null,db,null).tick();
   assertThat(state()).isEqualTo("QUARANTINED");
   assertThat(db.sql("select safe_failure_code from ouf_udp.materialization_job").query(String.class).single())
       .isEqualTo("UDP_SEMANTIC_REFERENCE_UNRESOLVABLE");
   var event=db.sql("select safe_detail->>'index' idx,safe_detail->>'semanticId' id,safe_detail->>'revisionId' rev from ouf_udp.handoff_event where event_type='REFERENCE_INTEGRITY_QUARANTINED'").query().singleRow();
   assertThat(event).containsEntry("idx","1007").containsEntry("id",peer.id(1007)).containsEntry("rev",peer.revision(1007));
   assertThat(db.sql("select count(*) from ouf_udp.materialization_observation where handoff_id='missing-pin'").query(Long.class).single()).isZero();
   assertThat(db.sql("select count(*) from ouf_udp.handoff_event where event_type='REFERENCE_INTEGRITY_PASSED'").query(Long.class).single()).isZero();
   assertThat(peer.starts).containsExactly(0,1000,0,1000);
   var status=intake.handoff("missing-pin");
   assertThat(status).containsEntry("failure_code","UDP_SEMANTIC_REFERENCE_UNRESOLVABLE");
   assertThat(((com.fasterxml.jackson.databind.JsonNode)status.get("referenceFailure")).required("index").asInt()).isEqualTo(1007);
   verifyNoInteractions(resolver,materializer);
  }
 }
 private ResolutionRepository.Claim claim(String worker){return jobs.claim(worker,Duration.ofMinutes(2)).orElseThrow();}
 private String state(){return db.sql("select state from ouf_udp.materialization_job").query(String.class).single();}
 private int attempts(){return db.sql("select dependency_attempts from ouf_udp.materialization_job").query(Integer.class).single();}
 private void due(){db.sql("update ouf_udp.materialization_job set next_integrity_check_at=transaction_timestamp()-interval '1 second'").update();}
 private HistoricalContractCatalog.Resolution ready(){return new HistoricalContractCatalog.Resolution(List.of(),List.of(),"sha256:"+"a".repeat(64));}
 private HandoffIntakeService.Receipt accept(String id,ReferenceBatchFixture peer){
  var values=new LinkedHashMap<String,Object>();
  for(int i=0;i<ReferenceBatchFixture.COUNT;i++)values.put(peer.id(i),id+"-"+i);
  return accept(id,peer.refs,values,"cameras","FILE");
 }
 private void accept(String id){
  accept(id,Map.of("sourceSchemaRef","schema://road/1","bundleRef","bundle://road/1","semanticPublicationSetRef","semantic://publication/1","adapterProfileRef","adapter://rest/1"),Map.of("code",id,"name","Road"),"roads","ROAD");
 }
 private HandoffIntakeService.Receipt accept(String id,Map<String,Object> refs,Map<String,Object> values,String source,String type){
  return intake.accept(new LinkedHashMap<>(Map.ofEntries(Map.entry("handoffId",id),Map.entry("ingestionRunId","run-"+id),Map.entry("ingestionId","ing-"+id),
   Map.entry("sourceIdentity",Map.of("sourceId",source,"typeCode",type,"sourceObjectId",id,"observedAt","2026-01-01T00:00:00Z")),Map.entry("operation","UPSERT"),
   Map.entry("canonicalPayload",values),Map.entry("contractRefs",refs),
   Map.entry("lineageId","lineage-"+id),Map.entry("contentHash","sha256:"+"d".repeat(64)),Map.entry("acquiredAt","2026-01-01T00:00:00Z"),Map.entry("changeRepresentation",Map.of("mode","FULL_SNAPSHOT")))));
 }
}
