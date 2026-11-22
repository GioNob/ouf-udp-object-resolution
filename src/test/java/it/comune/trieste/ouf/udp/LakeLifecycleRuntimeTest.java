package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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

  @ParameterizedTest
  @ValueSource(strings={"NORMALIZED","CURATED"})
  void failedWriteRestagesTheSameObjectAndLocator(String tier){
    storage.beforePut=()->{throw new IllegalStateException("put failed");};
    assertThatThrownBy(()->store("same","OPEN",tier)).hasMessage("put failed");
    storage.beforePut=()->{};
    var failed=catalog.get(db.sql("select lake_object_id from ouf_udp.lake_object").query(UUID.class).single());
    assertThat(failed.state()).isEqualTo("FAILED");
    var recovered=store("same","OPEN",tier);
    assertThat(recovered.id()).isEqualTo(failed.id());
    assertThat(recovered.locator()).isEqualTo(failed.locator());
    assertThat(recovered.retentionUntil()).isEqualTo(failed.retentionUntil());
    assertThat(recovered.state()).isEqualTo("VERIFIED");
    assertThat(recovered.version()).isEqualTo(failed.version()+3);
    assertThat(store("same","OPEN",tier)).isEqualTo(recovered);
    assertThat(storage.puts.get()).isEqualTo(2);
    assertThat(copyNumbers()).containsExactly(0L);
    assertThat(db.sql("""
        select actor_subject||':'||(safe_detail->>'reason') from ouf_udp.lake_object_event
        where lake_object_id=:id and from_state='FAILED' and to_state='STAGED'
        """).param("id",failed.id()).query(String.class).list()).containsExactly("SYSTEM:UDP_LAKE_IDENTICAL_CONTENT_RETRY");
  }

  @Test void concurrentFailedWriteRetryReturns503UntilTheClaimedWriteFinishes() throws Exception {
    storage.failDurability=true;
    assertThatThrownBy(()->store("concurrent","OPEN","NORMALIZED")).hasMessageContaining("DURABILITY");
    storage.failDurability=false;
    var putEntered=new CountDownLatch(1);
    var finishPut=new CountDownLatch(1);
    storage.beforePut=()->{putEntered.countDown();await(finishPut);};
    try(var executor=Executors.newSingleThreadExecutor()){
      var winner=executor.submit(()->store("concurrent","OPEN","NORMALIZED"));
      try{
        await(putEntered);
        upload("concurrent","NORMALIZED").andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("UDP_LAKE_WRITE_CONTENDED"));
        assertThat(storage.puts.get()).isEqualTo(2);
      }finally{
        finishPut.countDown();
      }
      var verified=winner.get(10,TimeUnit.SECONDS);
      assertThat(verified.state()).isEqualTo("VERIFIED");
      upload("concurrent","NORMALIZED").andExpect(status().isCreated())
          .andExpect(jsonPath("$.objectRef").value("lake://"+verified.id()));
      assertThat(storage.puts.get()).isEqualTo(2);
      assertThat(copyNumbers()).containsExactly(0L);
    }
  }

  @Test void recoveryPreservesFailedAndQuarantinedCopiesAndReusesTheLatestVerifiedCopy() throws Exception {
    storage.failDurability=true;
    assertThatThrownBy(()->store("raw-replacement","OPEN")).hasMessageContaining("DURABILITY");
    storage.failDurability=false;
    var failed=catalog.get(db.sql("select lake_object_id from ouf_udp.lake_object").query(UUID.class).single());
    assertThat(failed.state()).isEqualTo("FAILED");
    var restored=store("raw-replacement","OPEN");
    storage.corrupt(restored.locator());
    lake.reconcile();
    var quarantined=catalog.get(restored.id());
    assertThat(quarantined.state()).isEqualTo("QUARANTINED");
    assertThatThrownBy(()->store("raw-replacement","RESTRICTED")).hasMessage("UDP_LAKE_COPY_POLICY_CONFLICT");

    upload("raw-replacement","RAW").andExpect(status().isCreated());
    var latest=store("raw-replacement","OPEN");
    assertThat(latest.state()).isEqualTo("VERIFIED");
    upload("raw-replacement","RAW").andExpect(status().isCreated())
        .andExpect(jsonPath("$.objectRef").value("lake://"+latest.id()));
    assertThat(copyNumbers()).containsExactly(0L,1L,2L);
    assertThat(storage.puts.get()).isEqualTo(3);
    assertThat(catalog.get(failed.id())).isEqualTo(failed);
    assertThat(catalog.get(restored.id())).isEqualTo(quarantined);
    assertThat(List.of(failed,quarantined,latest)).allSatisfy(copy->{
      assertThat(copy.locator()).endsWith("/"+copy.id());
      assertThat(copy.leaseGeneration()).isZero();
    });
    assertThat(storage.read(failed.locator())).isEqualTo("raw-replacement".getBytes(StandardCharsets.UTF_8));
    assertThat(storage.read(quarantined.locator())).isEqualTo("tampered".getBytes(StandardCharsets.UTF_8));
    assertThat(storage.read(latest.locator())).isEqualTo("raw-replacement".getBytes(StandardCharsets.UTF_8));
  }

  @ParameterizedTest
  @ValueSource(strings={"NORMALIZED","CURATED"})
  void quarantinedObjectReturns409AndAllowsGovernedDeletion(String tier) throws Exception {
    var original=store("quarantine","OPEN",tier);
    storage.corrupt(original.locator());
    lake.reconcile();
    var quarantined=catalog.get(original.id());
    upload("quarantine",tier).andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("UDP_LAKE_OBJECT_QUARANTINED"));
    assertThat(catalog.get(original.id())).isEqualTo(quarantined);
    assertThat(storage.puts.get()).isOne();
    assertThat(copyNumbers()).containsExactly(0L);
    assertThat(storage.read(original.locator())).isEqualTo("tampered".getBytes(StandardCharsets.UTF_8));
    assertThat(lake.authorizeDeletion(original.id(),quarantined.version(),"expired fixture",human()).state())
        .isEqualTo("DELETION_PENDING");
    upload("quarantine",tier).andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("UDP_LAKE_OBJECT_NOT_WRITABLE"));
  }

  @Test void catalogPolicyDriftReturns409WithoutRestagingFailedObject() throws Exception {
    storage.failDurability=true;
    assertThatThrownBy(()->store("policy-conflict","RESTRICTED","CURATED")).hasMessageContaining("DURABILITY");
    var failed=catalog.get(db.sql("select lake_object_id from ouf_udp.lake_object").query(UUID.class).single());
    upload("policy-conflict","CURATED").andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("UDP_LAKE_COPY_POLICY_CONFLICT"));
    assertThat(catalog.get(failed.id())).isEqualTo(failed);
    assertThat(storage.puts.get()).isOne();
  }

  @ParameterizedTest
  @ValueSource(strings={"RAW","NORMALIZED","CURATED"})
  void interruptedStagedCopyCompletesAtItsOriginalLocator(String tier){
    byte[] bytes="staged".getBytes(StandardCharsets.UTF_8);
    var staged=catalog.stage(UUID.randomUUID(),"default","source","TYPE",tier,RuntimeLakeApi.hash(bytes),"application/json",
        bytes.length,"lake/staged-original","OPERATIONAL","OPEN",OffsetDateTime.now(),"one");
    var verified=store("staged","OPEN",tier);
    assertThat(verified.id()).isEqualTo(staged.id());
    assertThat(verified.locator()).isEqualTo(staged.locator());
    assertThat(verified.state()).isEqualTo("VERIFIED");
    assertThat(storage.puts.get()).isOne();
    assertThat(copyNumbers()).containsExactly(0L);
  }

  @Test void expiredWriteClaimCannotPublishAndStagedObjectCanResume(){
    byte[] bytes="expired-claim".getBytes(StandardCharsets.UTF_8);
    var staged=catalog.stage(UUID.randomUUID(),"default","source","TYPE","CURATED",RuntimeLakeApi.hash(bytes),"application/json",
        bytes.length,"lake/expired-claim","OPERATIONAL","OPEN",OffsetDateTime.now(),"one");
    db.sql("""
        update ouf_udp.lake_object set lease_owner='old-worker',lease_generation=1,
          lease_until=transaction_timestamp()-interval '1 second',state_version=state_version+1
        where lake_object_id=:id
        """).param("id",staged.id()).update();
    var oldClaim=catalog.get(staged.id());
    assertThat(catalog.advanceWrite(oldClaim,"DURABLE","old-correlation",Map.of())).isEmpty();
    var recovered=store("expired-claim","OPEN","CURATED");
    assertThat(recovered.id()).isEqualTo(oldClaim.id());
    assertThat(recovered.state()).isEqualTo("VERIFIED");
    assertThat(recovered.leaseGeneration()).isEqualTo(2);
    assertThat(catalog.advanceWrite(oldClaim,"FAILED","old-correlation",Map.of())).isEmpty();
    assertThat(catalog.get(staged.id())).isEqualTo(recovered);
  }
  @Test void interruptedDurableCopyIsReadBackBeforeReuse(){
    byte[] bytes="resume".getBytes(StandardCharsets.UTF_8);
    String hash=RuntimeLakeApi.hash(bytes);
    var staged=catalog.stage(UUID.randomUUID(),"default","source","TYPE","RAW",hash,"application/json",
        bytes.length,"lake/interrupted","OPERATIONAL","OPEN",OffsetDateTime.now(),"one");
    storage.put(staged.locator(),bytes,"application/json",hash);
    catalog.transition(staged.id(),staged.version(),"STAGED","DURABLE","SYSTEM","","one",Map.of());
    assertThat(store("resume","OPEN").id()).isEqualTo(staged.id());
    assertThat(catalog.get(staged.id()).state()).isEqualTo("VERIFIED");
    assertThat(storage.puts.get()).isOne();
  }

  @Test void interruptedVerificationKeepsDurableCheckpointForAReadOnlyRetry(){
    storage.headFailure=new IllegalStateException("head failed");
    assertThatThrownBy(()->store("verification-interrupted","OPEN","NORMALIZED")).hasMessage("head failed");
    var durable=catalog.get(db.sql("select lake_object_id from ouf_udp.lake_object").query(UUID.class).single());
    assertThat(durable.state()).isEqualTo("DURABLE");
    assertThat(durable.leaseOwner()).isNull();
    storage.headFailure=null;
    var verified=store("verification-interrupted","OPEN","NORMALIZED");
    assertThat(verified.id()).isEqualTo(durable.id());
    assertThat(verified.state()).isEqualTo("VERIFIED");
    assertThat(storage.puts.get()).isOne();
  }

  @Test void invalidVerificationReturns409AndQuarantinesTheDurableCopy() throws Exception {
    byte[] bytes="corrupt-durable".getBytes(StandardCharsets.UTF_8);
    var staged=catalog.stage(UUID.randomUUID(),"default","source","TYPE","CURATED",RuntimeLakeApi.hash(bytes),"application/json",
        bytes.length,"lake/corrupt-durable","OPERATIONAL","OPEN",OffsetDateTime.now(),"one");
    catalog.transition(staged.id(),staged.version(),"STAGED","DURABLE","SYSTEM","","one",Map.of());
    storage.corrupt(staged.locator());
    upload("corrupt-durable","CURATED").andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("UDP_LAKE_OBJECT_QUARANTINED"));
    assertThat(catalog.get(staged.id()).state()).isEqualTo("QUARANTINED");
    assertThat(storage.puts.get()).isZero();
  }
  @Test void compactedBytesKeepOriginalManifestAfterFreshUpload(){
    var a=store("first","OPEN");var b=store("second","OPEN");
    lake.compact(List.of(a.id(),b.id()),"pack");
    var segment=lake.verifyCompacted(catalog.get(b.id()));
    assertThat(segment.offset()).isEqualTo(a.size());
    var fresh=store("second","OPEN");
    assertThat(fresh.state()).isEqualTo("VERIFIED");
    assertThat(fresh.hash()).isEqualTo(b.hash());
    assertThat(fresh.id()).isNotEqualTo(b.id());
    assertThat(fresh.locator()).isNotEqualTo(b.locator());
    assertThat(lake.verifyCompacted(catalog.get(b.id()))).isEqualTo(segment);
    assertThat(catalog.get(b.id()).state()).isEqualTo("COMPACTED");
    storage.corrupt(segment.locator());
    assertThatThrownBy(()->lake.verifyCompacted(catalog.get(b.id()))).hasMessage("UDP_LAKE_CHECKSUM_MISMATCH");
  }

  private LakeCatalogRepository.LakeObject store(String value,String label){return store(value,label,"RAW");}
  private LakeCatalogRepository.LakeObject store(String value,String label,String tier){return lake.store(value.getBytes(StandardCharsets.UTF_8),"default","source","TYPE",tier,"application/json","OPERATIONAL",label,OffsetDateTime.now().minusSeconds(1),"corr-"+value);}
  private List<Long> copyNumbers(){return db.sql("select copy_no from ouf_udp.lake_object order by copy_no").query(Long.class).list();}
  private ResultActions upload(String value,String tier) throws Exception {
    var authorization=mock(RuntimeIntakeAuthorization.class);
    when(authorization.require(any(),eq("datalake.write"),eq("source"),anyString())).thenReturn("default");
    var http=MockMvcBuilders.standaloneSetup(new RuntimeLakeApi(authorization,lake,1,"OPERATIONAL","OPEN")).build();
    byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
    return http.perform(post("/api/internal/v1/lake/objects").contentType("application/json").content("""
        {"runId":"%s","sourceId":"source","typeCode":"TYPE","zone":"%s","contentBase64":"%s","contentHash":"%s"}
        """.formatted(UUID.randomUUID(),tier,Base64.getEncoder().encodeToString(bytes),RuntimeLakeApi.hash(bytes))));
  }
  private static void await(CountDownLatch latch){
    try{
      if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("Timed out waiting for storage test barrier");
    }catch(InterruptedException interrupted){
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
  private static TrustedHumanContext human(){return new TrustedHumanContext("HUMAN_USER","operator","default",Set.of("lake.object.delete"),"authz://lake","corr-delete");}
  @TestConfiguration static class Config {@Bean MemoryStorage storage(){return new MemoryStorage();}}
  static class MemoryStorage implements LakeObjectStoragePort {
    final Map<String,byte[]> data=new ConcurrentHashMap<>();
    final AtomicInteger puts=new AtomicInteger();
    volatile boolean failDurability;
    RuntimeException headFailure;
    volatile Runnable beforePut=()->{};
    public StoredObject put(String key,byte[] content,String media,String expected){puts.incrementAndGet();beforePut.run();data.put(key,content.clone());return new StoredObject(key,expected,content.length,!failDurability);}
    public Optional<StoredObject> head(String locator){if(headFailure!=null)throw headFailure;byte[] value=data.get(locator);return value==null?Optional.empty():Optional.of(new StoredObject(locator,hash(value),value.length,true));}
    public byte[] read(String locator){return data.get(locator).clone();}
    public Collection<StoredObject> inventory(){return data.entrySet().stream().map(e->new StoredObject(e.getKey(),hash(e.getValue()),e.getValue().length,true)).toList();}
    public void delete(String locator){data.remove(locator);}
    void corrupt(String locator){data.put(locator,"tampered".getBytes(StandardCharsets.UTF_8));}void orphan(String locator){data.put(locator,"orphan".getBytes(StandardCharsets.UTF_8));}void clear(){data.clear();puts.set(0);failDurability=false;headFailure=null;beforePut=()->{};}
    private static String hash(byte[] bytes){try{return "sha256:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
  }
  private static String required(String n){String v=System.getenv(n);if(v==null)throw new IllegalStateException(n+" required");return v;}
}
