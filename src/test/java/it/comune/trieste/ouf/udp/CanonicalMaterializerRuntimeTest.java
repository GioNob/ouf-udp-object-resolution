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
class CanonicalMaterializerRuntimeTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_UDP_DB_URL"));r.add("spring.datasource.username",()->required("OUF_UDP_DB_USER"));r.add("spring.datasource.password",()->required("OUF_UDP_DB_PASSWORD"));}
  @Autowired HandoffIntakeService intake;@Autowired ObjectResolutionService resolution;@Autowired CanonicalMaterializer materializer;@Autowired JdbcClient db;
  private final UdpPorts.ResolutionProfile resolutionProfile=new UdpPorts.ResolutionProfile("CANONICAL_KEY","1","policy://resolution/1","ouf:Road","code","code");
  private final UdpPorts.MaterializationProfile authorityProfile=new UdpPorts.MaterializationProfile("policy://authority/1",List.of(new UdpPorts.PropertyRule("name","ouf:name","string","OPEN",List.of("registry","survey")),new UdpPorts.PropertyRule("surface","ouf:surface","number","RESTRICTED",List.of("survey","registry"))));

  @BeforeEach void clean(){db.sql("truncate table ouf_udp.property_conflict,ouf_udp.property_value,ouf_udp.materialization_observation,ouf_udp.property_contribution,ouf_udp.object_revision,ouf_udp.resolution_issue,ouf_udp.resolution_decision,ouf_udp.source_binding,ouf_udp.urban_object,ouf_udp.materialization_job,ouf_udp.handoff_event,ouf_udp.handoff_intake restart identity cascade").update();}

  @Test void createsImmutableRevisionWithPropertyLabelsAndFullProvenance(){Fixture f=resolved("h-1","registry","r-1","R1","Via Roma",42);var result=materializer.materialize("h-1",f.objectId,f.payload,authorityProfile);assertThat(result.materialChange()).isTrue();assertThat(db.sql("select revision from ouf_udp.urban_object where urban_object_id=:u").param("u",f.objectId).query(Long.class).single()).isOne();assertThat(db.sql("select access_label from ouf_udp.property_value where property_iri='ouf:surface'").query(String.class).single()).isEqualTo("RESTRICTED");String provenance=db.sql("select provenance_json::text from ouf_udp.property_contribution where property_iri='ouf:name'").query(String.class).single();assertThat(provenance).contains("lineage-h-1","bundle://road/1","semantic://publication/1","raw://h-1");assertThatThrownBy(()->db.sql("delete from ouf_udp.object_revision where revision_id=:r").param("r",result.revisionId()).update()).hasStackTraceContaining("append-only");}

  @Test void identicalCanonicalObservationDoesNotCreateAnotherRevision(){Fixture first=resolved("h-a","registry","r-a","R1","Via Roma",42);var a=materializer.materialize("h-a",first.objectId,first.payload,authorityProfile);Fixture second=resolved("h-b","registry","r-b","R1","Via Roma",42);assertThat(second.objectId).isEqualTo(first.objectId);var b=materializer.materialize("h-b",second.objectId,second.payload,authorityProfile);assertThat(b.materialChange()).isFalse();assertThat(b.revisionId()).isEqualTo(a.revisionId());assertThat(db.sql("select count(*) from ouf_udp.object_revision").query(Long.class).single()).isOne();assertThat(db.sql("select count(*) from ouf_udp.materialization_observation").query(Long.class).single()).isEqualTo(2);}

  @Test void propertyAuthorityIsSpecificAndNotLastWriteWins(){Fixture registry=resolved("h-reg","registry","r-reg","R1","Authoritative",10);materializer.materialize("h-reg",registry.objectId,registry.payload,authorityProfile);Fixture survey=resolved("h-survey","survey","r-survey","R1","Later but fallback",99);materializer.materialize("h-survey",survey.objectId,survey.payload,authorityProfile);String current=db.sql("select canonical_payload::text from ouf_udp.object_revision where revision_id=(select current_revision_id from ouf_udp.urban_object where urban_object_id=:u)").param("u",registry.objectId).query(String.class).single();assertThat(current).contains("Authoritative","99").doesNotContain("Later but fallback","10");}

  @Test void equalAuthorityConflictCreatesIssueAndPreservesCurrentValue(){var open=new UdpPorts.MaterializationProfile("policy://authority/tie",List.of(new UdpPorts.PropertyRule("name","ouf:name","string","OPEN",List.of())));Fixture a=resolved("h-c1","source-a","c1","R1","First",1);materializer.materialize("h-c1",a.objectId,a.payload,open);Fixture b=resolved("h-c2","source-b","c2","R1","Second",1);var result=materializer.materialize("h-c2",b.objectId,b.payload,open);assertThat(result.materialChange()).isFalse();assertThat(db.sql("select count(*) from ouf_udp.property_conflict where state='OPEN'").query(Long.class).single()).isOne();String current=db.sql("select canonical_payload::text from ouf_udp.object_revision where revision_id=(select current_revision_id from ouf_udp.urban_object where urban_object_id=:u)").param("u",a.objectId).query(String.class).single();assertThat(current).contains("First").doesNotContain("Second");}

  @Test void weightedIdentityMatchesDifferentSourceKeysAndPersistsEvidence(){
    var mapped=new UdpPorts.MaterializationProfile("policy://weighted-fixture",List.of(new UdpPorts.PropertyRule("name","name","string","OPEN",List.of()),new UdpPorts.PropertyRule("surface","surface","number","OPEN",List.of())));
    Fixture first=resolved("weighted-a","registry","native-a","KEY-A","Via Roma",42);
    materializer.materialize("weighted-a",first.objectId,first.payload,mapped);
    var policy=new WeightedIdentity.Policy(List.of(new WeightedIdentity.Signal("name","TEXT",1,null)),List.of("surface"),null,10,.85,.5,.1,false);
    var weighted=new UdpPorts.ResolutionProfile("COMPOSITE","1","policy://weighted/1","ouf:Road","code","code",policy);
    var incoming=handoff("weighted-b","survey","native-b","DIFFERENT-KEY","Via Romo",42);intake.accept(incoming);
    var decision=resolution.resolve("weighted-b",incoming,weighted);
    assertThat(decision.outcome()).isEqualTo("MATCH");assertThat(decision.targetUrbanObjectId()).isEqualTo(first.objectId);
    assertThat(db.sql("select confidence from ouf_udp.resolution_decision where handoff_id='weighted-b'").query(Double.class).single()).isEqualTo(.875);
    assertThat(db.sql("select score_evidence::text from ouf_udp.resolution_decision where handoff_id='weighted-b'").query(String.class).single()).contains("HIGH_CONFIDENCE_UNIQUE","name:TEXT").doesNotContain("Via Romo");
    assertThat(resolution.resolve("weighted-b",incoming,weighted).duplicate()).isTrue();
  }

  private Fixture resolved(String handoff,String source,String object,String code,String name,int surface){Map<String,Object> payload=handoff(handoff,source,object,code,name,surface);intake.accept(payload);var decision=resolution.resolve(handoff,payload,resolutionProfile);return new Fixture(decision.targetUrbanObjectId(),payload);}
  private static Map<String,Object> handoff(String id,String source,String object,String code,String name,int surface){return new LinkedHashMap<>(Map.ofEntries(Map.entry("handoffId",id),Map.entry("ingestionRunId","run-1"),Map.entry("ingestionId","ing-"+id),Map.entry("sourceIdentity",new LinkedHashMap<>(Map.of("sourceId",source,"typeCode","ROAD","sourceObjectId",object,"observedAt","2026-09-12T00:00:00Z"))),Map.entry("operation","UPSERT"),Map.entry("canonicalPayload",Map.of("code",code,"name",name,"surface",surface)),Map.entry("rawObjectRef","raw://"+id),Map.entry("contractRefs",Map.of("sourceSchemaRef","schema://road/1","bundleRef","bundle://road/1","semanticPublicationSetRef","semantic://publication/1","adapterProfileRef","adapter://rest/1")),Map.entry("lineageId","lineage-"+id),Map.entry("contentHash","sha256:"+id),Map.entry("acquiredAt","2026-09-12T00:00:00Z"),Map.entry("changeRepresentation",Map.of("mode","FULL_SNAPSHOT"))));}
  private static String required(String n){String v=System.getenv(n);if(v==null)throw new IllegalStateException(n+" required");return v;}
  private record Fixture(UUID objectId,Map<String,Object> payload){}
}
