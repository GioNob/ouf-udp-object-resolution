package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@Import(MaterializationReferenceGateRuntimeTest.Config.class)
class MaterializationReferenceGateRuntimeTest {
  private static final Path CATALOG=createCatalog();
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_UDP_DB_URL"));r.add("spring.datasource.username",()->required("OUF_UDP_DB_USER"));r.add("spring.datasource.password",()->required("OUF_UDP_DB_PASSWORD"));r.add("ouf.udp.lake.required",()->"false");r.add("ouf.udp.historical-contracts.catalog-path",CATALOG::toString);r.add("ouf.udp.reference-integrity.max-attempts",()->"2");r.add("ouf.udp.reference-integrity.retry-base-ms",()->"1");}
  @Autowired HandoffIntakeService intake;@Autowired ResolutionRepository jobs;@Autowired MaterializationReferenceGate gate;@Autowired ResolutionWorker worker;@Autowired JdbcClient db;@Autowired Counters counters;

  @BeforeEach void clean() throws Exception {Files.writeString(CATALOG,"[]");counters.resolution().set(0);counters.materialization().set(0);db.sql("truncate table ouf_udp.replay_event,ouf_udp.replay_plan,ouf_udp.property_conflict,ouf_udp.property_value,ouf_udp.materialization_observation,ouf_udp.property_contribution,ouf_udp.object_revision,ouf_udp.resolution_issue,ouf_udp.resolution_decision,ouf_udp.source_binding,ouf_udp.urban_object,ouf_udp.materialization_job,ouf_udp.handoff_event,ouf_udp.handoff_intake restart identity cascade").update();}

  @Test void missingReferencePausesBeforeConfigurationOrMaterializationAndRetriesWhenRestored() throws Exception {
    accept("gate-1");assertThat(worker.executeOne("worker-a")).contains("gate-1");assertJob("gate-1","PAUSED","UDP_REFERENCE_INTEGRITY_MISSING");assertThat(counters.resolution()).hasValue(0);assertThat(counters.materialization()).hasValue(0);
    writeCatalog("a");makeRetryDue("gate-1");assertThat(worker.executeOne("worker-b")).contains("gate-1");assertJob("gate-1","SUCCEEDED",null);assertThat(counters.resolution()).hasValue(1);assertThat(counters.materialization()).hasValue(1);assertThat(db.sql("select integrity_baseline_hash from ouf_udp.materialization_job where handoff_id='gate-1'").query(String.class).single()).startsWith("sha256:");
  }

  @Test void repeatedMissingReferenceIsQuarantinedAtConfiguredBound() {
    accept("gate-2");worker.executeOne("worker-a");makeRetryDue("gate-2");worker.executeOne("worker-b");assertJob("gate-2","QUARANTINED","UDP_REFERENCE_INTEGRITY_MISSING");assertThat(counters.resolution()).hasValue(0);assertThat(db.sql("select count(*) from ouf_udp.handoff_event where handoff_id='gate-2' and event_type='REFERENCE_INTEGRITY_QUARANTINED'").query(Long.class).single()).isOne();
  }

  @Test void sameReferenceWithChangedContentHashIsQuarantinedWithoutFallback() throws Exception {
    accept("gate-3");writeCatalog("a");var claim=jobs.claim("crashed-worker",java.time.Duration.ofMinutes(1)).orElseThrow();assertThat(gate.verify(claim)).isTrue();db.sql("update ouf_udp.materialization_job set lease_until=transaction_timestamp()-interval '1 second' where handoff_id='gate-3'").update();writeCatalog("b");worker.executeOne("recovery-worker");assertJob("gate-3","QUARANTINED","UDP_REFERENCE_INTEGRITY_DRIFT");assertThat(counters.resolution()).hasValue(0);assertThat(counters.materialization()).hasValue(0);
  }

  private void accept(String handoff){intake.accept(new LinkedHashMap<>(Map.ofEntries(Map.entry("handoffId",handoff),Map.entry("ingestionRunId","run-"+handoff),Map.entry("ingestionId","ing-"+handoff),Map.entry("sourceIdentity",Map.of("sourceId","roads","typeCode","ROAD","sourceObjectId",handoff)),Map.entry("operation","UPSERT"),Map.entry("canonicalPayload",Map.of("code",handoff,"name","Road")),Map.entry("contractRefs",refs()),Map.entry("lineageId","lineage-"+handoff),Map.entry("contentHash","sha256:"+"d".repeat(64)),Map.entry("acquiredAt","2026-01-01T00:00:00Z"),Map.entry("changeRepresentation",Map.of("mode","FULL_SNAPSHOT")))));}
  private void makeRetryDue(String handoff){db.sql("update ouf_udp.materialization_job set next_integrity_check_at=transaction_timestamp()-interval '1 second' where handoff_id=:h").param("h",handoff).update();}
  private void assertJob(String handoff,String state,String code){Map<String,Object> row=db.sql("select state,safe_failure_code,missing_ref_hashes::text missing from ouf_udp.materialization_job where handoff_id=:h").param("h",handoff).query().singleRow();assertThat(row.get("state")).isEqualTo(state);assertThat(row.get("safe_failure_code")).isEqualTo(code);if("UDP_REFERENCE_INTEGRITY_MISSING".equals(code))assertThat(String.valueOf(row.get("missing"))).contains("sha256:");}
  private static Map<String,Object> refs(){return Map.of("sourceSchemaRef","schema://road/1","bundleRef","bundle://road/1","semanticPublicationSetRef","semantic://publication/1","adapterProfileRef","adapter://rest/1");}
  private static Path createCatalog(){try{Path p=Files.createTempFile("udp-materialization-contracts-",".json");p.toFile().deleteOnExit();return p;}catch(Exception e){throw new ExceptionInInitializerError(e);}}
  private static void writeCatalog(String value) throws Exception {String hash="sha256:"+value.repeat(64);Files.writeString(CATALOG,"["+String.join(",",entry("SOURCE_SCHEMA","schema://road/1",hash),entry("BUNDLE","bundle://road/1",hash),entry("SEMANTIC_PUBLICATION_SET","semantic://publication/1",hash),entry("ADAPTER_PROFILE","adapter://rest/1",hash))+"]");}
  private static String entry(String kind,String ref,String hash){return "{\"kind\":\""+kind+"\",\"ref\":\""+ref+"\",\"version\":\"1\",\"contentHash\":\""+hash+"\",\"status\":\"AVAILABLE\"}";}
  private static String required(String name){String value=System.getenv(name);if(value==null)throw new IllegalStateException(name+" required");return value;}

  record Counters(AtomicInteger resolution,AtomicInteger materialization){}
  @TestConfiguration static class Config {
    @Bean Counters counters(){return new Counters(new AtomicInteger(),new AtomicInteger());}
    @Bean UdpPorts.ResolutionConfigurationPort resolutionConfig(Counters c){return (bundle,type)->{c.resolution().incrementAndGet();return new UdpPorts.ResolutionProfile("CANONICAL_KEY","1","policy://resolution/1","ouf:Road","code","code");};}
    @Bean UdpPorts.MaterializationConfigurationPort materializationConfig(Counters c){return (bundle,type)->{c.materialization().incrementAndGet();return new UdpPorts.MaterializationProfile("policy://authority/1",List.of(new UdpPorts.PropertyRule("name","ouf:name","string","OPEN",List.of("roads"))));};}
    @Bean @Primary ResolutionWorker gateWorker(ResolutionRepository jobs,MaterializationReferenceGate gate,ObjectResolutionService resolution,CanonicalMaterializer materializer,UdpPorts.ResolutionConfigurationPort resolutionConfig,UdpPorts.MaterializationConfigurationPort materializationConfig){return new ResolutionWorker(jobs,gate,resolution,materializer,resolutionConfig,materializationConfig);}
  }
}
