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
  when(catalog.resolve(anyMap())).thenReturn(ready());
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
 private ResolutionRepository.Claim claim(String worker){return jobs.claim(worker,Duration.ofMinutes(2)).orElseThrow();}
 private String state(){return db.sql("select state from ouf_udp.materialization_job").query(String.class).single();}
 private int attempts(){return db.sql("select dependency_attempts from ouf_udp.materialization_job").query(Integer.class).single();}
 private void due(){db.sql("update ouf_udp.materialization_job set next_integrity_check_at=transaction_timestamp()-interval '1 second'").update();}
 private HistoricalContractCatalog.Resolution ready(){return new HistoricalContractCatalog.Resolution(List.of(),List.of(),"sha256:"+"a".repeat(64));}
 private void accept(String id){
  intake.accept(new LinkedHashMap<>(Map.ofEntries(Map.entry("handoffId",id),Map.entry("ingestionRunId","run-"+id),Map.entry("ingestionId","ing-"+id),
   Map.entry("sourceIdentity",Map.of("sourceId","roads","typeCode","ROAD","sourceObjectId",id)),Map.entry("operation","UPSERT"),
   Map.entry("canonicalPayload",Map.of("code",id,"name","Road")),Map.entry("contractRefs",Map.of("sourceSchemaRef","schema://road/1","bundleRef","bundle://road/1","semanticPublicationSetRef","semantic://publication/1","adapterProfileRef","adapter://rest/1")),
   Map.entry("lineageId","lineage-"+id),Map.entry("contentHash","sha256:"+"d".repeat(64)),Map.entry("acquiredAt","2026-01-01T00:00:00Z"),Map.entry("changeRepresentation",Map.of("mode","FULL_SNAPSHOT")))));
 }
}
