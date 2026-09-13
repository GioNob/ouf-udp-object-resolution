package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinioLakeAcceptanceTest {
  private static final String BUCKET="ouf-udp-acceptance";
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_UDP_DB_URL"));r.add("spring.datasource.username",()->required("OUF_UDP_DB_USER"));r.add("spring.datasource.password",()->required("OUF_UDP_DB_PASSWORD"));r.add("ouf.udp.lake.s3.bucket",()->BUCKET);r.add("ouf.udp.lake.s3.endpoint",()->"http://127.0.0.1:9000");r.add("ouf.udp.lake.s3.region",()->"us-east-1");r.add("ouf.udp.lake.s3.path-style",()->true);r.add("ouf.udp.lake.shadow.initial-delay-ms",()->3600000);r.add("ouf.udp.lake.maintenance.initial-delay-ms",()->3600000);}
  @Autowired S3Client s3;@Autowired S3LakeObjectStorageAdapter storage;@Autowired LakeLifecycleService lake;@Autowired LakeCatalogRepository catalog;@Autowired LakeShadowRebuildService rebuild;@Autowired JdbcClient db;

  @BeforeAll void bucket(){if(s3.listBuckets().buckets().stream().noneMatch(b->BUCKET.equals(b.name())))s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());}
  @BeforeEach void clean(){for(S3Object object:s3.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build()).contents())s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(object.key()).build());db.sql("truncate table ouf_udp.lake_shadow_rebuild_event,ouf_udp.lake_shadow_rebuild_entry,ouf_udp.lake_shadow_rebuild_plan,ouf_udp.storage_reconciliation_finding,ouf_udp.compaction_manifest_entry,ouf_udp.compaction_manifest,ouf_udp.lake_object_event,ouf_udp.lake_object restart identity cascade").update();}

  @Test void realSdkRoundTripPreservesDurabilityMetadataAndInventory(){byte[] content="minio-round-trip".getBytes(StandardCharsets.UTF_8);String hash=hash(content);var put=storage.put("acceptance/round-trip",content,"application/json",hash);assertThat(put.durable()).isTrue();assertThat(put.contentHash()).isEqualTo(hash);assertThat(storage.head(put.locator())).contains(put);assertThat(storage.read(put.locator())).isEqualTo(content);assertThat(storage.inventory()).extracting(LakeObjectStoragePort.StoredObject::locator).containsExactly("acceptance/round-trip");storage.delete(put.locator());assertThat(storage.head(put.locator())).isEmpty();}

  @Test void realStorageFaultsAreDetectedWithoutDeletingOrphanEvidence(){var object=store("source","tenant-minio");byte[] tampered="tampered".getBytes(StandardCharsets.UTF_8);s3.putObject(PutObjectRequest.builder().bucket(BUCKET).key(object.locator()).metadata(Map.of("ouf-content-hash",hash(tampered))).build(),RequestBody.fromBytes(tampered));byte[] orphan="orphan".getBytes(StandardCharsets.UTF_8);storage.put("lake/orphan-acceptance",orphan,"application/octet-stream",hash(orphan));var scan=lake.reconcile();assertThat(scan.checksumMismatch()).isEqualTo(1);assertThat(scan.orphan()).isEqualTo(1);assertThat(catalog.get(object.id()).state()).isEqualTo("QUARANTINED");assertThat(storage.head("lake/orphan-acceptance")).isPresent();}

  @Test void realStorageShadowRebuildVerifiesAndCutsOverPhysicalLocator(){var object=store("shadow","tenant-minio");String original=object.locator();var plan=rebuild.plan("RAW",human("tenant-minio","lake.shadow.plan"));var claim=rebuild.claim("minio-worker",300).orElseThrow();var ready=rebuild.build(claim);assertThat(ready.state()).isEqualTo("READY");assertThat(ready.shadowDigest()).isEqualTo(ready.baselineDigest());var cutover=rebuild.cutover(plan.id(),ready.version(),"MinIO acceptance verified",human("tenant-minio","lake.shadow.cutover"));assertThat(cutover.state()).isEqualTo("CUTOVER");var current=catalog.get(object.id());assertThat(current.id()).isEqualTo(object.id());assertThat(current.previousLocator()).isEqualTo(original);assertThat(current.locator()).startsWith("shadow/"+plan.id());assertThat(storage.head(current.locator())).isPresent();assertThat(storage.head(original)).isPresent();}

  private LakeCatalogRepository.LakeObject store(String value,String tenant){return lake.store(value.getBytes(StandardCharsets.UTF_8),tenant,"source","TYPE","RAW","application/json","ARCHIVAL","OPEN",OffsetDateTime.now().plusDays(30),"corr-"+value);}
  private static TrustedHumanContext human(String tenant,String capability){return new TrustedHumanContext("HUMAN_USER","acceptance-operator",tenant,Set.of(capability),"authz://minio-acceptance","corr-minio");}
  private static String hash(byte[] bytes){try{return "sha256:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
  private static String required(String name){String value=System.getenv(name);if(value==null)throw new IllegalStateException(name+" required");return value;}
}
