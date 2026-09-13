package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
class LakeShadowRebuildRuntimeTest {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_UDP_DB_URL"));r.add("spring.datasource.username",()->required("OUF_UDP_DB_USER"));r.add("spring.datasource.password",()->required("OUF_UDP_DB_PASSWORD"));r.add("ouf.udp.lake.shadow.initial-delay-ms",()->3600000);r.add("ouf.udp.lake.maintenance.initial-delay-ms",()->3600000);r.add("ouf.udp.lake.shadow.worker-id",()->"shadow-test-worker");}
  @Autowired LakeLifecycleService lake;@Autowired LakeShadowRebuildService rebuild;@Autowired LakeShadowRebuildWorker worker;@Autowired ShadowStorage storage;@Autowired JdbcClient db;

  @BeforeEach void clean(){storage.data.clear();db.sql("truncate table ouf_udp.lake_shadow_rebuild_event,ouf_udp.lake_shadow_rebuild_entry,ouf_udp.lake_shadow_rebuild_plan,ouf_udp.storage_reconciliation_finding,ouf_udp.compaction_manifest_entry,ouf_udp.compaction_manifest,ouf_udp.lake_object_event,ouf_udp.lake_object restart identity cascade").update();}

  @Test void shadowCopyIsVerifiedBeforeHumanCutoverAndLogicalIdsRemainStable(){var a=store("a","tenant-a");store("b","tenant-a");String oldA=a.locator();var plan=rebuild.plan("RAW",human("tenant-a",Set.of("lake.shadow.plan")));assertThat(plan.expectedObjectCount()).isEqualTo(2);assertThat(worker.runOnce()).isTrue();var ready=rebuild.get(plan.id(),"tenant-a");assertThat(ready.state()).isEqualTo("READY");assertThat(ready.shadowDigest()).isEqualTo(ready.baselineDigest());var cutover=rebuild.cutover(plan.id(),ready.version(),"validated storage migration",human("tenant-a",Set.of("lake.shadow.cutover")));assertThat(cutover.state()).isEqualTo("CUTOVER");Map<String,Object> object=db.sql("select lake_object_id,locator,previous_locator,content_hash from ouf_udp.lake_object where lake_object_id=:id").param("id",a.id()).query().singleRow();assertThat(object.get("lake_object_id")).isEqualTo(a.id());assertThat(object.get("previous_locator")).isEqualTo(oldA);assertThat(String.valueOf(object.get("locator"))).startsWith("shadow/"+plan.id());assertThat(object.get("content_hash")).isEqualTo(a.hash());assertThat(db.sql("select event_type from ouf_udp.lake_shadow_rebuild_event order by occurred_at").query(String.class).list()).containsExactly("SHADOW_REBUILD_PLANNED","SHADOW_REBUILD_READY","SHADOW_REBUILD_CUTOVER");assertThat(storage.data).hasSize(4);}

  @Test void changedSourceLocatorBlocksCutoverWithoutPartialMutation(){var object=store("drift","tenant-a");var plan=rebuild.plan("RAW",human("tenant-a",Set.of("lake.shadow.plan")));worker.runOnce();var ready=rebuild.get(plan.id(),"tenant-a");db.sql("update ouf_udp.lake_object set locator='lake/drifted',state_version=state_version+1 where lake_object_id=:id").param("id",object.id()).update();assertThatThrownBy(()->rebuild.cutover(plan.id(),ready.version(),"must fail",human("tenant-a",Set.of("lake.shadow.cutover")))).hasMessageContaining("REFERENCE_GATE");assertThat(rebuild.get(plan.id(),"tenant-a").state()).isEqualTo("READY");assertThat(db.sql("select state from ouf_udp.lake_shadow_rebuild_entry where plan_id=:id").param("id",plan.id()).query(String.class).single()).isEqualTo("VERIFIED");}

  @Test void plansAreTenantScopedAndCutoverRequiresHumanCapability(){store("scope","tenant-a");var plan=rebuild.plan("RAW",human("tenant-a",Set.of("lake.shadow.plan")));assertThatThrownBy(()->rebuild.get(plan.id(),"tenant-b")).isInstanceOf(ResponseStatusException.class);worker.runOnce();var ready=rebuild.get(plan.id(),"tenant-a");var machine=new TrustedHumanContext("SERVICE_IDENTITY","robot","tenant-a",Set.of("lake.shadow.cutover"),"authz://machine","corr-machine");assertThatThrownBy(()->rebuild.cutover(plan.id(),ready.version(),"machine forbidden",machine)).isInstanceOf(SecurityException.class);}

  private LakeCatalogRepository.LakeObject store(String value,String tenant){return lake.store(value.getBytes(StandardCharsets.UTF_8),tenant,"source","TYPE","RAW","application/json","ARCHIVAL","OPEN",OffsetDateTime.now().plusDays(30),"corr-"+value);}
  private static TrustedHumanContext human(String tenant,Set<String> caps){return new TrustedHumanContext("HUMAN_USER","operator",tenant,caps,"authz://shadow","corr-shadow");}
  @TestConfiguration static class Config {@Bean ShadowStorage storage(){return new ShadowStorage();}}
  static class ShadowStorage implements LakeObjectStoragePort {final Map<String,byte[]> data=new ConcurrentHashMap<>();public StoredObject put(String key,byte[] content,String media,String hash){data.put(key,content.clone());return new StoredObject(key,hash,content.length,true);}public Optional<StoredObject> head(String locator){byte[] bytes=data.get(locator);return bytes==null?Optional.empty():Optional.of(new StoredObject(locator,hash(bytes),bytes.length,true));}public byte[] read(String locator){byte[] bytes=data.get(locator);if(bytes==null)throw new IllegalStateException("UDP_LAKE_OBJECT_MISSING");return bytes.clone();}public Collection<StoredObject> inventory(){return data.entrySet().stream().map(e->new StoredObject(e.getKey(),hash(e.getValue()),e.getValue().length,true)).toList();}public void delete(String locator){data.remove(locator);}private static String hash(byte[] bytes){try{return "sha256:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}}
  private static String required(String name){String value=System.getenv(name);if(value==null)throw new IllegalStateException(name+" required");return value;}
}

