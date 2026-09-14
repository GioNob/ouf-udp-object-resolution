package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class RelationshipMaterializerRuntimeTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_UDP_DB_URL"));r.add("spring.datasource.username",()->required("OUF_UDP_DB_USER"));r.add("spring.datasource.password",()->required("OUF_UDP_DB_PASSWORD"));}
  @Autowired ObjectMapper json;@Autowired HandoffIntakeService intake;@Autowired ObjectResolutionService resolution;@Autowired CanonicalMaterializer canonical;@Autowired RelationshipMaterializer relationships;@Autowired JdbcClient db;
  private final UdpPorts.MaterializationProfile properties=new UdpPorts.MaterializationProfile("policy://authority/1",List.of(new UdpPorts.PropertyRule("code","ouf:code","string","OPEN",List.of())));
  private final UdpPorts.RelationshipProfile relationProfile=new UdpPorts.RelationshipProfile("policy://relationships/1",List.of(new UdpPorts.RelationshipRule("streetRef","ouf:locatedOn","ouf:Road","ouf:code","CANONICAL_KEY","QUARANTINE_RELATION","RESTRICTED",false)));

  @BeforeEach void clean(){db.sql("truncate table ouf_udp.relationship_issue,ouf_udp.relationship_revision,ouf_udp.relationship_contribution,ouf_udp.urban_relationship,ouf_udp.property_conflict,ouf_udp.property_value,ouf_udp.materialization_observation,ouf_udp.property_contribution,ouf_udp.object_revision,ouf_udp.resolution_issue,ouf_udp.resolution_decision,ouf_udp.source_binding,ouf_udp.urban_object,ouf_udp.materialization_job,ouf_udp.handoff_event,ouf_udp.handoff_intake restart identity cascade").update();}

  @Test void resolvesAndVersionsGovernedEdgeWithProvenanceAndLabel(){create("road-h","roads","road-1","ROAD","ouf:Road","R1","R1",null);Fixture worksite=create("work-h","worksites","work-1","WORKSITE","ouf:Worksite","W1","W1","R1");var result=relationships.materialize("work-h",worksite.objectId,worksite.payload,relationProfile);assertThat(result).isEqualTo(new RelationshipMaterializer.Result(1,0,0));assertThat(db.sql("select count(*) from ouf_udp.urban_relationship where relation_iri='ouf:locatedOn'").query(Long.class).single()).isOne();assertThat(db.sql("select access_label from ouf_udp.relationship_revision").query(String.class).single()).isEqualTo("RESTRICTED");String evidence=db.sql("select resolution_evidence::text from ouf_udp.relationship_revision").query(String.class).single();assertThat(evidence).contains("CANONICAL_KEY","urban-object://");}

  @Test void noMatchWithQuarantinePolicyCreatesIssueAndNoEdge(){Fixture worksite=create("missing-h","worksites","work-2","WORKSITE","ouf:Worksite","W2","W2","UNKNOWN");var result=relationships.materialize("missing-h",worksite.objectId,worksite.payload,relationProfile);assertThat(result.quarantined()).isOne();assertThat(db.sql("select reason_code from ouf_udp.relationship_issue").query(String.class).single()).isEqualTo("NO_MATCH");assertThat(db.sql("select on_no_match from ouf_udp.relationship_issue").query(String.class).single()).isEqualTo("QUARANTINE_RELATION");assertThat(db.sql("select count(*) from ouf_udp.urban_relationship").query(Long.class).single()).isZero();}

  @Test void multipleMatchesRequireReviewWithoutArbitraryEdge(){create("road-a","roads","road-a","ROAD","ouf:Road","A","SAME",null);create("road-b","roads","road-b","ROAD","ouf:Road","B","SAME",null);Fixture worksite=create("multi-h","worksites","work-3","WORKSITE","ouf:Worksite","W3","W3","SAME");relationships.materialize("multi-h",worksite.objectId,worksite.payload,relationProfile);assertThat(db.sql("select reason_code from ouf_udp.relationship_issue").query(String.class).single()).isEqualTo("MULTIPLE_MATCHES");assertThat(db.sql("select jsonb_array_length(candidate_refs) from ouf_udp.relationship_issue").query(Integer.class).single()).isEqualTo(2);assertThat(db.sql("select count(*) from ouf_udp.urban_relationship").query(Long.class).single()).isZero();}

  @Test void consumesExactIngestionPairwiseFixtureAndQuarantinesUnresolvedRelationship() throws Exception {
    Map<String,Object> payload=json.readValue(Objects.requireNonNull(getClass().getResourceAsStream("/pairwise/ingestion-to-udp-relationship-handoff.json")),new TypeReference<>(){});
    intake.accept(payload);var rp=new UdpPorts.ResolutionProfile("CANONICAL_KEY","1","policy://resolution/1","ouf:Worksite","identity","identity");UUID object=resolution.resolve("pairwise-worksite-1",payload,rp).targetUrbanObjectId();canonical.materialize("pairwise-worksite-1",object,payload,properties);
    var result=relationships.materialize("pairwise-worksite-1",object,payload,relationProfile);
    assertThat(result).isEqualTo(new RelationshipMaterializer.Result(0,1,0));assertThat(db.sql("select reason_code||':'||on_no_match from ouf_udp.relationship_issue").query(String.class).single()).isEqualTo("NO_MATCH:QUARANTINE_RELATION");
    Map<String,Object> canonicalPayload=(Map<String,Object>)payload.get("canonicalPayload");Map<String,Object> contractRefs=(Map<String,Object>)payload.get("contractRefs");assertThat(canonicalPayload).containsEntry("streetRef","UNKNOWN");assertThat(contractRefs.get("relationshipResolutionStrategyRefs")).isEqualTo(List.of("relationship://located-on/1"));
  }

  @Test void retryIsIdempotentAndRelationshipEvidenceIsAppendOnly(){create("road-i","roads","road-i","ROAD","ouf:Road","R9","R9",null);Fixture worksite=create("idem-h","worksites","work-i","WORKSITE","ouf:Worksite","W9","W9","R9");relationships.materialize("idem-h",worksite.objectId,worksite.payload,relationProfile);relationships.materialize("idem-h",worksite.objectId,worksite.payload,relationProfile);assertThat(db.sql("select count(*) from ouf_udp.relationship_contribution").query(Long.class).single()).isOne();assertThat(db.sql("select count(*) from ouf_udp.relationship_revision").query(Long.class).single()).isOne();assertThatThrownBy(()->db.sql("delete from ouf_udp.relationship_revision").update()).hasStackTraceContaining("append-only");}

  private Fixture create(String handoff,String source,String sourceObject,String typeCode,String canonicalType,String identity,String code,String streetRef){Map<String,Object> payload=handoff(handoff,source,sourceObject,typeCode,identity,code,streetRef);intake.accept(payload);var rp=new UdpPorts.ResolutionProfile("CANONICAL_KEY","1","policy://resolution/1",canonicalType,"identity","identity");UUID object=resolution.resolve(handoff,payload,rp).targetUrbanObjectId();canonical.materialize(handoff,object,payload,properties);return new Fixture(object,payload);}
  private static Map<String,Object> handoff(String id,String source,String object,String type,String identity,String code,String streetRef){Map<String,Object> canonical=new LinkedHashMap<>();canonical.put("identity",identity);canonical.put("code",code);if(streetRef!=null)canonical.put("streetRef",streetRef);return new LinkedHashMap<>(Map.ofEntries(Map.entry("handoffId",id),Map.entry("ingestionRunId","run-1"),Map.entry("ingestionId","ing-"+id),Map.entry("sourceIdentity",new LinkedHashMap<>(Map.of("sourceId",source,"typeCode",type,"sourceObjectId",object,"observedAt","2026-09-12T00:00:00Z"))),Map.entry("operation","UPSERT"),Map.entry("canonicalPayload",canonical),Map.entry("rawObjectRef","raw://"+id),Map.entry("contractRefs",Map.of("sourceSchemaRef","schema://1","bundleRef","bundle://1","semanticPublicationSetRef","semantic://1","adapterProfileRef","adapter://1","relationshipResolutionStrategyRefs",List.of("relationship://located-on/1"))),Map.entry("lineageId","lineage-"+id),Map.entry("contentHash","sha256:"+id),Map.entry("acquiredAt","2026-09-12T00:00:00Z"),Map.entry("changeRepresentation",Map.of("mode","FULL_SNAPSHOT"))));}
  private static String required(String n){String v=System.getenv(n);if(v==null)throw new IllegalStateException(n+" required");return v;}
  private record Fixture(UUID objectId,Map<String,Object> payload){}
}
