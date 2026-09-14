package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@Import(HistoricalReplayRuntimeTest.Config.class)
class HistoricalReplayRuntimeTest {
  private static final Path CATALOG=createCatalog();
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_UDP_DB_URL"));r.add("spring.datasource.username",()->required("OUF_UDP_DB_USER"));r.add("spring.datasource.password",()->required("OUF_UDP_DB_PASSWORD"));r.add("ouf.udp.lake.required",()->"false");r.add("ouf.udp.historical-contracts.catalog-path",CATALOG::toString);}
  @Autowired HandoffIntakeService intake;@Autowired HistoricalReplayService replay;@Autowired ResolutionWorker worker;@Autowired JdbcClient db;

  @BeforeEach void clean() throws Exception {writeCatalog(true,"a");db.sql("truncate table ouf_udp.replay_event,ouf_udp.replay_plan,ouf_udp.lake_object_event,ouf_udp.materialization_job,ouf_udp.handoff_event,ouf_udp.handoff_intake,ouf_udp.lake_object restart identity cascade").update();}

  @Test void reproducePinsHistoricalBaselineAndCreatesIdempotentDurableWork() throws Exception {
    Source source=source("original-1");TrustedHumanContext actor=actor(Set.of("udp.replay.plan","udp.replay.execute"));var plan=replay.plan(source.handoffId(),"audit reproduction",actor);assertThat(plan.state()).isEqualTo("READY");assertThat(plan.baselineRefs()).isEqualTo(refs());assertThat(plan.baselineHash()).startsWith("sha256:");
    var executed=replay.execute(plan.replayPlanId(),plan.version(),actor);assertThat(executed.state()).isEqualTo("SUCCEEDED");assertThat(executed.replayHandoffId()).startsWith("replay-");assertThat(replay.execute(plan.replayPlanId(),plan.version(),actor)).isEqualTo(executed);
    Map<String,Object> stored=db.sql("select payload_json::text payload,raw_lake_object_id from ouf_udp.handoff_intake where handoff_id=:h").param("h",executed.replayHandoffId()).query().singleRow();assertThat(stored.get("raw_lake_object_id")).isEqualTo(source.rawId());assertThat(String.valueOf(stored.get("payload"))).contains("schema://road/1","bundle://road/1","semantic://publication/1","adapter://rest/1");assertThat(db.sql("select count(*) from ouf_udp.materialization_job where handoff_id=:h").param("h",executed.replayHandoffId()).query(Long.class).single()).isOne();assertThat(db.sql("select count(*) from ouf_udp.handoff_intake where raw_lake_object_id=:raw").param("raw",source.rawId()).query(Long.class).single()).isEqualTo(2);
    db.sql("update ouf_udp.materialization_job set state='SUCCEEDED' where handoff_id=:h").param("h",source.handoffId()).update();assertThat(worker.executeOne("reproduce-worker")).contains(executed.replayHandoffId());assertThat(db.sql("select state from ouf_udp.materialization_job where handoff_id=:h").param("h",executed.replayHandoffId()).query(String.class).single()).isEqualTo("SUCCEEDED");assertThat(db.sql("select bundle_ref from ouf_udp.object_revision where source_handoff_id=:h").param("h",executed.replayHandoffId()).query(String.class).single()).isEqualTo("bundle://road/1");
  }

  @Test void missingHistoricalReferencePausesAndCanResumeWithoutActiveFallback() throws Exception {
    Source source=source("original-2");writeCatalog(false,"a");TrustedHumanContext actor=actor(Set.of("udp.replay.plan"));var paused=replay.plan(source.handoffId(),"missing semantic baseline",actor);assertThat(paused.state()).isEqualTo("PAUSED");assertThat(paused.missingRefHashes()).hasSize(1).allMatch(x->x.startsWith("sha256:"));assertThat(db.sql("select count(*) from ouf_udp.handoff_intake").query(Long.class).single()).isOne();
    assertThat(replay.resume(paused.replayPlanId(),paused.version(),actor).state()).isEqualTo("PAUSED");writeCatalog(true,"a");var ready=replay.resume(paused.replayPlanId(),paused.version(),actor);assertThat(ready.state()).isEqualTo("READY");assertThat(ready.version()).isEqualTo(1);
  }

  @Test void changedHistoricalArtifactBlocksExecutionInsteadOfReinterpretingWithCurrent() throws Exception {
    Source source=source("original-3");TrustedHumanContext actor=actor(Set.of("udp.replay.plan","udp.replay.execute"));var plan=replay.plan(source.handoffId(),"verify immutable baseline",actor);writeCatalog(true,"b");var paused=replay.execute(plan.replayPlanId(),plan.version(),actor);assertThat(paused.state()).isEqualTo("PAUSED");assertThat(paused.baselineHash()).isNull();assertThat(db.sql("select count(*) from ouf_udp.handoff_intake").query(Long.class).single()).isOne();assertThat(db.sql("select event_type from ouf_udp.replay_event order by occurred_at desc limit 1").query(String.class).single()).isEqualTo("REPRODUCE_PAUSED");
  }

  @Test void replayExecutionRequiresCurrentTrustedHumanAuthorization() {
    Source source=source("original-4");var planner=actor(Set.of("udp.replay.plan"));var plan=replay.plan(source.handoffId(),"authorization boundary",planner);assertThatThrownBy(()->replay.execute(plan.replayPlanId(),plan.version(),planner)).isInstanceOf(SecurityException.class).hasMessageContaining("UDP_TRUSTED_HUMAN_REQUIRED");assertThatThrownBy(()->replay.execute(plan.replayPlanId(),plan.version(),new TrustedHumanContext("SERVICE_IDENTITY","worker","tenant-a",Set.of("udp.replay.execute"),"authz://2","corr-2"))).isInstanceOf(SecurityException.class);
  }

  private Source source(String handoff){Map<String,Object> payload=new LinkedHashMap<>(Map.ofEntries(Map.entry("handoffId",handoff),Map.entry("ingestionRunId","run-old"),Map.entry("ingestionId","ing-old"),Map.entry("sourceIdentity",new LinkedHashMap<>(Map.of("sourceId","roads","typeCode","ROAD","sourceObjectId","road-1"))),Map.entry("operation","UPSERT"),Map.entry("canonicalPayload",Map.of("code","R1","name","Historical Road")),Map.entry("contractRefs",refs()),Map.entry("lineageId","lineage-old"),Map.entry("contentHash","sha256:original"),Map.entry("acquiredAt","2026-01-01T00:00:00Z"),Map.entry("changeRepresentation",Map.of("mode","FULL_SNAPSHOT"))));intake.accept(payload);UUID raw=UUID.randomUUID();db.sql("insert into ouf_udp.lake_object(lake_object_id,tenant_id,source_id,type_code,tier,content_hash,media_type,logical_size_bytes,locator,state,retention_class,access_label,retention_until,historical_replay_required,verified_at) values(:id,'tenant-a','roads','ROAD','RAW','sha256:original','application/json',1,:locator,'VERIFIED','AUDIT','OPEN',:retention,true,transaction_timestamp())").param("id",raw).param("locator","s3://raw/"+handoff).param("retention",OffsetDateTime.now().plusDays(30)).update();db.sql("update ouf_udp.handoff_intake set raw_lake_object_id=:raw where handoff_id=:h").param("raw",raw).param("h",handoff).update();return new Source(handoff,raw);}
  private static Map<String,Object> refs(){return new LinkedHashMap<>(Map.of("sourceSchemaRef","schema://road/1","bundleRef","bundle://road/1","semanticPublicationSetRef","semantic://publication/1","adapterProfileRef","adapter://rest/1"));}
  private static TrustedHumanContext actor(Set<String> capabilities){return new TrustedHumanContext("HUMAN_USER","operator","tenant-a",capabilities,"authz://1","corr-1");}
  private static Path createCatalog(){try{Path p=Files.createTempFile("udp-historical-contracts-",".json");p.toFile().deleteOnExit();return p;}catch(Exception e){throw new ExceptionInInitializerError(e);}}
  private static void writeCatalog(boolean semantic,String hash) throws Exception {String digest="sha256:"+hash.repeat(64),newer="sha256:"+"c".repeat(64);List<String> rows=new ArrayList<>(List.of(entry("SOURCE_SCHEMA","schema://road/1",digest),entry("BUNDLE","bundle://road/1",digest),entry("ADAPTER_PROFILE","adapter://rest/1",digest),entry("SOURCE_SCHEMA","schema://road/2",newer),entry("BUNDLE","bundle://road/2",newer)));if(semantic){rows.add(entry("SEMANTIC_PUBLICATION_SET","semantic://publication/1",digest));rows.add(entry("SEMANTIC_PUBLICATION_SET","semantic://publication/2",newer));}Files.writeString(CATALOG,"["+String.join(",",rows)+"]");}
  private static String entry(String kind,String ref,String hash){return "{\"kind\":\""+kind+"\",\"ref\":\""+ref+"\",\"version\":\"1\",\"contentHash\":\""+hash+"\",\"status\":\"AVAILABLE\"}";}
  private static String required(String n){String v=System.getenv(n);if(v==null)throw new IllegalStateException(n+" required");return v;}
  private record Source(String handoffId,UUID rawId){}
  @TestConfiguration static class Config {
    @Bean UdpPorts.ResolutionConfigurationPort resolutionConfig(){return (bundle,type)->{if(!"bundle://road/1".equals(bundle))throw new IllegalStateException("UDP_HISTORICAL_BUNDLE_NOT_USED");return new UdpPorts.ResolutionProfile("CANONICAL_KEY","1","policy://resolution/1","ouf:Road","code","code");};}
    @Bean UdpPorts.MaterializationConfigurationPort materializationConfig(){return (bundle,type)->{if(!"bundle://road/1".equals(bundle))throw new IllegalStateException("UDP_HISTORICAL_BUNDLE_NOT_USED");return new UdpPorts.MaterializationProfile("policy://authority/1",List.of(new UdpPorts.PropertyRule("name","ouf:name","string","OPEN",List.of("roads"))));};}
    @Bean @Primary ResolutionWorker historicalReplayWorker(ResolutionRepository jobs,ObjectResolutionService resolution,CanonicalMaterializer materializer,UdpPorts.ResolutionConfigurationPort resolutionConfig,UdpPorts.MaterializationConfigurationPort materializationConfig){return new ResolutionWorker(jobs,resolution,materializer,resolutionConfig,materializationConfig);}
  }
}
