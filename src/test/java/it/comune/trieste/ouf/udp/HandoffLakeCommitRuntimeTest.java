package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
class HandoffLakeCommitRuntimeTest {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_UDP_DB_URL"));r.add("spring.datasource.username",()->required("OUF_UDP_DB_USER"));r.add("spring.datasource.password",()->required("OUF_UDP_DB_PASSWORD"));r.add("ouf.udp.lake.required",()->true);r.add("ouf.udp.lake.raw-retention-days",()->365);r.add("ouf.udp.lake.raw-retention-class",()->"AUDIT");r.add("ouf.udp.lake.raw-access-label",()->"OPEN");r.add("ouf.udp.lake.tenant-id",()->"tenant-ci");}
  @Autowired HandoffIntakeService intake;@Autowired Storage storage;@Autowired JdbcClient db;
  @BeforeEach void clean(){storage.clear();db.sql("truncate table ouf_udp.storage_reconciliation_finding,ouf_udp.compaction_manifest_entry,ouf_udp.compaction_manifest,ouf_udp.lake_object_event,ouf_udp.query_budget,ouf_udp.serving_access_audit,ouf_udp.merge_resolution_issue,ouf_udp.merge_property_contribution_link,ouf_udp.human_resolution_decision,ouf_udp.split_resolution_issue,ouf_udp.relationship_identity_history,ouf_udp.source_binding_history,ouf_udp.object_identity_history,ouf_udp.governance_audit,ouf_udp.governance_plan,ouf_udp.spatial_resolution_issue,ouf_udp.urban_geometry_current,ouf_udp.urban_geometry,ouf_udp.relationship_issue,ouf_udp.relationship_revision,ouf_udp.relationship_contribution,ouf_udp.urban_relationship,ouf_udp.property_conflict,ouf_udp.property_value,ouf_udp.materialization_observation,ouf_udp.property_contribution,ouf_udp.object_revision,ouf_udp.resolution_issue,ouf_udp.resolution_decision,ouf_udp.source_binding,ouf_udp.urban_object,ouf_udp.materialization_job,ouf_udp.handoff_event,ouf_udp.handoff_intake,ouf_udp.lake_object restart identity cascade").update();}
  @Test void ackAndJobExistOnlyAfterRawLakeObjectIsVerified(){var receipt=intake.accept(handoff("lake-h"));assertThat(receipt.durable()).isTrue();Map<String,Object> row=db.sql("select h.raw_lake_object_id,l.state,l.tier,l.tenant_id from ouf_udp.handoff_intake h join ouf_udp.lake_object l on l.lake_object_id=h.raw_lake_object_id where h.handoff_id='lake-h'").query().singleRow();assertThat(row).containsEntry("state","VERIFIED").containsEntry("tier","RAW").containsEntry("tenant_id","tenant-ci");assertThat(db.sql("select count(*) from ouf_udp.materialization_job").query(Long.class).single()).isOne();assertThat(intake.accept(handoff("lake-h")).duplicate()).isTrue();assertThat(storage.objects).hasSize(1);}
  @Test void invalidDurabilityProofCreatesNoHandoffAckOrJob(){storage.failDurability=true;assertThatThrownBy(()->intake.accept(handoff("failed-h"))).hasMessageContaining("DURABILITY");assertThat(db.sql("select count(*) from ouf_udp.handoff_intake").query(Long.class).single()).isZero();assertThat(db.sql("select count(*) from ouf_udp.materialization_job").query(Long.class).single()).isZero();assertThat(db.sql("select state from ouf_udp.lake_object").query(String.class).single()).isEqualTo("FAILED");}
  private static Map<String,Object> handoff(String id){return new LinkedHashMap<>(Map.ofEntries(Map.entry("handoffId",id),Map.entry("ingestionRunId","run-1"),Map.entry("ingestionId","ing-"+id),Map.entry("sourceIdentity",new LinkedHashMap<>(Map.of("sourceId","roads","typeCode","ROAD","sourceObjectId","object-1"))),Map.entry("operation","UPSERT"),Map.entry("canonicalPayload",Map.of("code","R1","name","Main Street")),Map.entry("contractRefs",Map.of("sourceSchemaRef","schema://road/1","bundleRef","bundle://road/1","semanticPublicationSetRef","semantic://publication/1","adapterProfileRef","adapter://rest/1")),Map.entry("lineageId","lineage-"+id),Map.entry("contentHash","sha256:"+id),Map.entry("acquiredAt","2026-09-12T00:00:00Z"),Map.entry("changeRepresentation",Map.of("mode","FULL_SNAPSHOT"))));}
  @TestConfiguration static class Config {@Bean Storage storage(){return new Storage();}}
  static class Storage implements LakeObjectStoragePort {final Map<String,Entry> objects=new ConcurrentHashMap<>();boolean failDurability;public StoredObject put(String key,byte[] content,String media,String expected){objects.put(key,new Entry(content.clone(),expected));return new StoredObject(key,expected,content.length,!failDurability);}public Optional<StoredObject> head(String key){Entry e=objects.get(key);return e==null?Optional.empty():Optional.of(new StoredObject(key,e.hash,e.bytes.length,true));}public byte[] read(String key){return objects.get(key).bytes.clone();}public Collection<StoredObject> inventory(){return objects.entrySet().stream().map(e->new StoredObject(e.getKey(),e.getValue().hash,e.getValue().bytes.length,true)).toList();}public void delete(String key){objects.remove(key);}void clear(){objects.clear();failDurability=false;}record Entry(byte[] bytes,String hash){}}
  private static String required(String n){String v=System.getenv(n);if(v==null)throw new IllegalStateException(n+" required");return v;}
}

