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

@SpringBootTest
class LakeLifecycleRuntimeTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_UDP_DB_URL"));r.add("spring.datasource.username",()->required("OUF_UDP_DB_USER"));r.add("spring.datasource.password",()->required("OUF_UDP_DB_PASSWORD"));}
  @Autowired LakeLifecycleService lake;@Autowired LakeCatalogRepository catalog;@Autowired MemoryStorage storage;@Autowired JdbcClient db;
  @BeforeEach void clean(){storage.clear();db.sql("truncate table ouf_udp.storage_reconciliation_finding,ouf_udp.compaction_manifest_entry,ouf_udp.compaction_manifest,ouf_udp.lake_object_event,ouf_udp.lake_object,ouf_udp.query_budget,ouf_udp.serving_access_audit,ouf_udp.merge_resolution_issue,ouf_udp.merge_property_contribution_link,ouf_udp.human_resolution_decision,ouf_udp.split_resolution_issue,ouf_udp.relationship_identity_history,ouf_udp.source_binding_history,ouf_udp.object_identity_history,ouf_udp.governance_audit,ouf_udp.governance_plan,ouf_udp.spatial_resolution_issue,ouf_udp.urban_geometry_current,ouf_udp.urban_geometry,ouf_udp.relationship_issue,ouf_udp.relationship_revision,ouf_udp.relationship_contribution,ouf_udp.urban_relationship,ouf_udp.property_conflict,ouf_udp.property_value,ouf_udp.materialization_observation,ouf_udp.property_contribution,ouf_udp.object_revision,ouf_udp.resolution_issue,ouf_udp.resolution_decision,ouf_udp.source_binding,ouf_udp.urban_object,ouf_udp.materialization_job,ouf_udp.handoff_event,ouf_udp.handoff_intake restart identity cascade").update();}

  @Test void durableRegistrationRequiresPutAndHeadVerification(){var object=store("one","OPEN");assertThat(object.state()).isEqualTo("VERIFIED");assertThat(db.sql("select event_type from ouf_udp.lake_object_event order by occurred_at").query(String.class).list()).containsExactly("LAKE_OBJECT_STAGED","LAKE_OBJECT_STATE_CHANGED","LAKE_OBJECT_STATE_CHANGED");var duplicate=store("one","OPEN");assertThat(duplicate.id()).isEqualTo(object.id());assertThatThrownBy(()->db.sql("delete from ouf_udp.lake_object where lake_object_id=:id").param("id",object.id()).update()).hasMessageContaining("cannot be deleted");}
  @Test void failedDurabilityProofNeverBecomesVerified(){storage.failDurability=true;assertThatThrownBy(()->store("broken","OPEN")).hasMessageContaining("DURABILITY");assertThat(db.sql("select state from ouf_udp.lake_object").query(String.class).single()).isEqualTo("FAILED");}
  @Test void deletionGuardAndLeasedTwoPhasePurgeAreEnforced(){var object=store("delete","OPEN");db.sql("update ouf_udp.lake_object set historical_replay_required=true,state_version=state_version+1 where lake_object_id=:id").param("id",object.id()).update();assertThatThrownBy(()->lake.authorizeDeletion(object.id(),object.version()+1,"expired dataset",human())).hasMessageContaining("GUARD");db.sql("update ouf_udp.lake_object set historical_replay_required=false,state_version=state_version+1 where lake_object_id=:id").param("id",object.id()).update();var pending=lake.authorizeDeletion(object.id(),object.version()+2,"expired dataset",human());db.sql("update ouf_udp.lake_object set purge_after=transaction_timestamp()-interval '1 second',state_version=state_version+1 where lake_object_id=:id").param("id",object.id()).update();assertThat(lake.purge("worker-1",10,"corr-purge")).isOne();assertThat(catalog.get(object.id()).state()).isEqualTo("DELETED");assertThat(storage.inventory()).isEmpty();assertThat(pending.state()).isEqualTo("DELETION_PENDING");}
  @Test void compactionPreservesLogicalIdentityAndRejectsLabelCrossing(){var a=store("a","OPEN");var b=store("b","OPEN");var restricted=store("c","RESTRICTED");assertThatThrownBy(()->lake.compact(List.of(a.id(),restricted.id()),"corr-invalid")).hasMessageContaining("BOUNDARY");UUID manifest=lake.compact(List.of(a.id(),b.id()),"corr-compact");assertThat(catalog.get(a.id()).state()).isEqualTo("COMPACTED");assertThat(catalog.get(a.id()).hash()).isEqualTo(a.hash());assertThat(db.sql("select count(*) from ouf_udp.compaction_manifest_entry where manifest_id=:id").param("id",manifest).query(Long.class).single()).isEqualTo(2);}
  @Test void reconciliationQuarantinesMismatchAndReportsOrphanWithoutDeleting(){var object=store("scan","OPEN");storage.corrupt(object.locator());storage.orphan("lake/orphan");var scan=lake.reconcile();assertThat(scan.checksumMismatch()).isOne();assertThat(scan.orphan()).isOne();assertThat(catalog.get(object.id()).state()).isEqualTo("QUARANTINED");assertThat(storage.inventory()).extracting(LakeObjectStoragePort.StoredObject::locator).contains("lake/orphan");}

  private LakeCatalogRepository.LakeObject store(String value,String label){return lake.store(value.getBytes(StandardCharsets.UTF_8),"default","source","TYPE","RAW","application/json","OPERATIONAL",label,OffsetDateTime.now().minusSeconds(1),"corr-"+value);}
  private static TrustedHumanContext human(){return new TrustedHumanContext("HUMAN_USER","operator","default",Set.of("lake.object.delete"),"authz://lake","corr-delete");}
  @TestConfiguration static class Config {@Bean MemoryStorage storage(){return new MemoryStorage();}}
  static class MemoryStorage implements LakeObjectStoragePort {
    final Map<String,byte[]> data=new ConcurrentHashMap<>();boolean failDurability;
    public StoredObject put(String key,byte[] content,String media,String expected){data.put(key,content.clone());return new StoredObject(key,expected,content.length,!failDurability);}
    public Optional<StoredObject> head(String locator){byte[] value=data.get(locator);return value==null?Optional.empty():Optional.of(new StoredObject(locator,hash(value),value.length,true));}
    public byte[] read(String locator){return data.get(locator).clone();}
    public Collection<StoredObject> inventory(){return data.entrySet().stream().map(e->new StoredObject(e.getKey(),hash(e.getValue()),e.getValue().length,true)).toList();}
    public void delete(String locator){data.remove(locator);}
    void corrupt(String locator){data.put(locator,"tampered".getBytes(StandardCharsets.UTF_8));}void orphan(String locator){data.put(locator,"orphan".getBytes(StandardCharsets.UTF_8));}void clear(){data.clear();failDurability=false;}
    private static String hash(byte[] bytes){try{return "sha256:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
  }
  private static String required(String n){String v=System.getenv(n);if(v==null)throw new IllegalStateException(n+" required");return v;}
}
