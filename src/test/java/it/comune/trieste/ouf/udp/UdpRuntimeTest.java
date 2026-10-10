package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class UdpRuntimeTest {
  @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_UDP_DB_URL"));r.add("spring.datasource.username",()->required("OUF_UDP_DB_USER"));r.add("spring.datasource.password",()->required("OUF_UDP_DB_PASSWORD"));}
  @Autowired HandoffIntakeService intake;@Autowired ObjectResolutionService resolution;@Autowired JdbcClient db;
  private final UdpPorts.ResolutionProfile profile=new UdpPorts.ResolutionProfile("ATTRIBUTE_EXACT","1","policy://resolution/1","ouf:Road","code","name");

  @BeforeEach void clean(){db.sql("truncate table ouf_udp.resolution_issue,ouf_udp.resolution_decision,ouf_udp.source_binding,ouf_udp.urban_object,ouf_udp.materialization_job,ouf_udp.handoff_event,ouf_udp.handoff_intake restart identity cascade").update();}

  @Test void durableAckIsIdempotentAndConflictingReplayFails(){Map<String,Object> payload=handoff("h-1","source-object-1","R1","Main Street");var first=intake.accept(payload);var duplicate=intake.accept(payload);assertThat(first.durable()).isTrue();assertThat(duplicate).isEqualTo(new HandoffIntakeService.Receipt(first.receiptRef(),true,true));assertThat(db.sql("select count(*) from ouf_udp.materialization_job").query(Long.class).single()).isOne();Map<String,Object> conflicting=new LinkedHashMap<>(payload);conflicting.put("contentHash","sha256:different");assertThatThrownBy(()->intake.accept(conflicting)).hasMessageContaining("UDP_HANDOFF_IDEMPOTENCY_CONFLICT");}

  @Test void invalidHandoffIsRejectedBeforePersistence(){Map<String,Object> invalid=handoff("h-invalid","o","R1","Main");((Map<?,?>)invalid.get("sourceIdentity")).remove("sourceId");assertThatThrownBy(()->intake.accept(invalid)).isInstanceOf(FrozenContractValidator.ContractViolation.class).extracting("paths").asList().contains("$.sourceIdentity.sourceId");assertThat(db.sql("select count(*) from ouf_udp.handoff_intake").query(Long.class).single()).isZero();}

  @Test void resolutionCreatesThenMatchesByDeterministicEvidence(){Map<String,Object> first=handoff("h-new","o-1","R1","Main Street");intake.accept(first);var created=resolution.resolve("h-new",first,profile);assertThat(created.outcome()).isEqualTo("NEW_OBJECT");UUID target=db.sql("select urban_object_id from ouf_udp.source_binding where source_object_id='o-1'").query(UUID.class).single();Map<String,Object> second=handoff("h-match","o-2","R2","Main Street");intake.accept(second);var matched=resolution.resolve("h-match",second,profile);assertThat(matched.outcome()).isEqualTo("MATCH");assertThat(matched.targetUrbanObjectId()).isEqualTo(target);assertThat(db.sql("select count(*) from ouf_udp.urban_object").query(Long.class).single()).isOne();}

  @Test void ambiguousCandidatesCreateReviewIssueWithoutArbitraryBinding(){seed("R1","same");seed("R2","same");Map<String,Object> payload=handoff("h-review","o-3","R3","Same");intake.accept(payload);var decision=resolution.resolve("h-review",payload,profile);assertThat(decision.outcome()).isEqualTo("REVIEW_REQUIRED");assertThat(db.sql("select reason_code from ouf_udp.resolution_issue").query(String.class).single()).isEqualTo("UDP_RESOLUTION_AMBIGUOUS");assertThat(db.sql("select count(*) from ouf_udp.source_binding where source_object_id='o-3'").query(Long.class).single()).isZero();}

  @Test void resolutionDecisionAndEventsAreAppendOnly(){Map<String,Object> payload=handoff("h-history","o-4","R4","History");intake.accept(payload);resolution.resolve("h-history",payload,profile);assertThatThrownBy(()->db.sql("delete from ouf_udp.resolution_decision where handoff_id='h-history'").update()).hasStackTraceContaining("append-only");assertThatThrownBy(()->db.sql("delete from ouf_udp.handoff_event where handoff_id='h-history'").update()).hasStackTraceContaining("append-only");}


  @Test void sameAttributesAcrossDifferentNaturesNeverMatch(){
    var cinema=new UdpPorts.ResolutionProfile("ATTRIBUTE_EXACT","1","policy://cinema/1","schema:MovieTheater","code","name");
    var address=new UdpPorts.ResolutionProfile("ATTRIBUTE_EXACT","1","policy://address/1","schema:PostalAddress","code","name");
    var a=handoff("h-cinema","cinema-row","SAME-CODE","Via Roma 12");intake.accept(a);
    var first=resolution.resolve("h-cinema",a,cinema);
    var b=handoff("h-address","address-row","SAME-CODE","Via Roma 12");intake.accept(b);
    var second=resolution.resolve("h-address",b,address);
    assertThat(first.outcome()).isEqualTo("NEW_OBJECT");assertThat(second.outcome()).isEqualTo("NEW_OBJECT");
    assertThat(second.targetUrbanObjectId()).isNotEqualTo(first.targetUrbanObjectId());
    assertThat(db.sql("select count(*) from ouf_udp.urban_object").query(Long.class).single()).isEqualTo(2);
  }

  @Test void sourceBindingCannotBypassReclassification(){
    var a=handoff("h-original","same-row","R1","Via Roma 12");intake.accept(a);
    var original=resolution.resolve("h-original",a,profile);
    var b=handoff("h-reclassified","same-row","R1","Via Roma 12");intake.accept(b);
    var address=new UdpPorts.ResolutionProfile("ATTRIBUTE_EXACT","1","policy://address/1","schema:PostalAddress","code","name");
    var reviewed=resolution.resolve("h-reclassified",b,address);
    assertThat(reviewed.outcome()).isEqualTo("REVIEW_REQUIRED");assertThat(reviewed.targetUrbanObjectId()).isNull();
    assertThat(db.sql("select reason_code from ouf_udp.resolution_issue").query(String.class).single()).isEqualTo("UDP_SOURCE_BINDING_SCOPE_MISMATCH");
    assertThat(db.sql("select urban_object_id from ouf_udp.source_binding where source_object_id='same-row'").query(UUID.class).single()).isEqualTo(original.targetUrbanObjectId());
    assertThat(db.sql("select count(*) from ouf_udp.urban_object").query(Long.class).single()).isOne();
    assertThat(resolution.resolve("h-reclassified",b,address)).isEqualTo(new ObjectResolutionService.Decision("REVIEW_REQUIRED",null,true));
  }

  @Test void foreignTenantBindingCannotBeReusedOrExposed(){
    var a=handoff("h-tenant-old","tenant-row","R1","Main");intake.accept(a);var original=resolution.resolve("h-tenant-old",a,profile);
    db.sql("update ouf_udp.urban_object set tenant_id='another-tenant' where urban_object_id=:u").param("u",original.targetUrbanObjectId()).update();
    var b=handoff("h-tenant-new","tenant-row","R1","Main");intake.accept(b);var reviewed=resolution.resolve("h-tenant-new",b,profile);
    assertThat(reviewed.outcome()).isEqualTo("REVIEW_REQUIRED");assertThat(reviewed.targetUrbanObjectId()).isNull();
    assertThat(db.sql("select jsonb_array_length(candidate_refs) from ouf_udp.resolution_issue").query(Integer.class).single()).isZero();
  }

  @Test void inactiveBindingCannotBeReused(){
    var a=handoff("h-inactive-old","inactive-row","R1","Main");intake.accept(a);var original=resolution.resolve("h-inactive-old",a,profile);
    db.sql("update ouf_udp.urban_object set status='DELETED' where urban_object_id=:u").param("u",original.targetUrbanObjectId()).update();
    var b=handoff("h-inactive-new","inactive-row","R1","Main");intake.accept(b);
    assertThat(resolution.resolve("h-inactive-new",b,profile).outcome()).isEqualTo("REVIEW_REQUIRED");
    assertThat(db.sql("select count(*) from ouf_udp.urban_object").query(Long.class).single()).isOne();
  }

  @Test void duplicateReplayCannotBypassCurrentNatureGuard(){
    var a=handoff("h-replay-nature","same-row","R1","Main");intake.accept(a);resolution.resolve("h-replay-nature",a,profile);
    var changed=new UdpPorts.ResolutionProfile("ATTRIBUTE_EXACT","1","policy://address/1","schema:PostalAddress","code","name");
    assertThatThrownBy(()->resolution.resolve("h-replay-nature",a,changed)).isInstanceOf(SecurityException.class).hasMessage("UDP_RESOLUTION_REPLAY_TARGET_SCOPE_MISMATCH");
    assertThat(db.sql("select count(*) from ouf_udp.resolution_decision").query(Long.class).single()).isOne();
  }

  @Test void blankMatchAttributesDoNotProvideIdentityEvidence(){
    var a=handoff("h-blank-a","blank-a","R1","  ");intake.accept(a);var first=resolution.resolve("h-blank-a",a,profile);
    var b=handoff("h-blank-b","blank-b","R2","");intake.accept(b);var second=resolution.resolve("h-blank-b",b,profile);
    assertThat(first.outcome()).isEqualTo("NEW_OBJECT");assertThat(second.outcome()).isEqualTo("NEW_OBJECT");
    assertThat(second.targetUrbanObjectId()).isNotEqualTo(first.targetUrbanObjectId());
  }

  @Test void sameNatureBindingAndReplayPreserveIdentity(){
    var a=handoff("h-continuity-a","continuity","R1","Old name");intake.accept(a);var first=resolution.resolve("h-continuity-a",a,profile);
    var b=handoff("h-continuity-b","continuity","R1","New name");intake.accept(b);var second=resolution.resolve("h-continuity-b",b,profile);
    assertThat(second.outcome()).isEqualTo("MATCH");assertThat(second.targetUrbanObjectId()).isEqualTo(first.targetUrbanObjectId());
    assertThat(resolution.resolve("h-continuity-b",b,profile)).isEqualTo(new ObjectResolutionService.Decision("MATCH",first.targetUrbanObjectId(),true));
  }

  private void seed(String key,String match){db.sql("insert into ouf_udp.urban_object(urban_object_id,canonical_type,canonical_key,match_key) values(gen_random_uuid(),'ouf:Road',:k,:m)").param("k",key.toLowerCase()).param("m",match).update();}
  private static Map<String,Object> handoff(String id,String object,String code,String name){return new LinkedHashMap<>(Map.ofEntries(Map.entry("handoffId",id),Map.entry("ingestionRunId","run-1"),Map.entry("ingestionId","ing-"+id),Map.entry("sourceIdentity",new LinkedHashMap<>(Map.of("sourceId","roads","typeCode","ROAD","sourceObjectId",object))),Map.entry("operation","UPSERT"),Map.entry("canonicalPayload",Map.of("code",code,"name",name)),Map.entry("contractRefs",Map.of("sourceSchemaRef","schema://road/1","bundleRef","bundle://road/1","semanticPublicationSetRef","semantic://publication/1","adapterProfileRef","adapter://rest/1")),Map.entry("lineageId","lineage-"+id),Map.entry("contentHash","sha256:"+id),Map.entry("acquiredAt","2026-09-12T00:00:00Z"),Map.entry("changeRepresentation",Map.of("mode","FULL_SNAPSHOT"))));}
  private static String required(String n){String v=System.getenv(n);if(v==null)throw new IllegalStateException(n+" required");return v;}
}
