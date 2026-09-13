package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
class GovernedServingRuntimeTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_UDP_DB_URL"));r.add("spring.datasource.username",()->required("OUF_UDP_DB_USER"));r.add("spring.datasource.password",()->required("OUF_UDP_DB_PASSWORD"));}
  @Autowired HandoffIntakeService intake;@Autowired ObjectResolutionService resolution;@Autowired CanonicalMaterializer canonical;@Autowired RelationshipMaterializer relationship;@Autowired GovernedServingService serving;@Autowired JdbcClient db;
  private final UdpPorts.ResolutionProfile resolutionProfile=new UdpPorts.ResolutionProfile("CANONICAL_KEY","1","policy://resolution/1","ouf:Asset","identity","identity");
  private final UdpPorts.MaterializationProfile materialization=new UdpPorts.MaterializationProfile("policy://authority/1",List.of(new UdpPorts.PropertyRule("name","ouf:name","string","OPEN",List.of("registry")),new UdpPorts.PropertyRule("secret","ouf:secret","string","RESTRICTED",List.of("registry"))));
  private final ServingAuthorizationContext open=new ServingAuthorizationContext("AI_AGENT","agent-1","default",Set.of("urban.object.read","urban.object.history.read","urban.object.search","urban.relationship.read","lineage.object.read","lineage.property.read"),Set.of("OPEN","ANONYMOUS"),"authz://serve/1","corr-serve-1");

  @BeforeEach void clean(){db.sql("truncate table ouf_udp.serving_access_audit,ouf_udp.merge_resolution_issue,ouf_udp.merge_property_contribution_link,ouf_udp.human_resolution_decision,ouf_udp.split_resolution_issue,ouf_udp.relationship_identity_history,ouf_udp.source_binding_history,ouf_udp.object_identity_history,ouf_udp.governance_audit,ouf_udp.governance_plan,ouf_udp.spatial_resolution_issue,ouf_udp.urban_geometry_current,ouf_udp.urban_geometry,ouf_udp.relationship_issue,ouf_udp.relationship_revision,ouf_udp.relationship_contribution,ouf_udp.urban_relationship,ouf_udp.property_conflict,ouf_udp.property_value,ouf_udp.materialization_observation,ouf_udp.property_contribution,ouf_udp.object_revision,ouf_udp.resolution_issue,ouf_udp.resolution_decision,ouf_udp.source_binding,ouf_udp.urban_object,ouf_udp.materialization_job,ouf_udp.handoff_event,ouf_udp.handoff_intake restart identity cascade").update();}

  @Test void currentOmitsUnauthorizedPropertyBeforeSerializationAndAuditsRedaction(){Fixture f=materialize("h-1","asset-1","Visible","classified",null);Map<String,Object> view=serving.current(f.objectId,open);Map<String,Object> properties=map(view.get("properties"));assertThat(properties).containsEntry("ouf:name","Visible").doesNotContainKey("ouf:secret");assertThat(view.toString()).doesNotContain("classified");assertThat(db.sql("select outcome from ouf_udp.serving_access_audit").query(String.class).single()).isEqualTo("REDACTED");}

  @Test void historyUsesOpaqueCursorAndSameSourceUpdateCreatesRevision(){Fixture first=materialize("h-old","asset-1","Old","s1",null);Fixture second=materialize("h-new","asset-1","New","s2",null);assertThat(second.objectId).isEqualTo(first.objectId);var page1=serving.history(first.objectId,1,null,null,open);assertThat(page1.items()).hasSize(1);assertThat(page1.nextCursor()).isNotBlank().doesNotContain("1");assertThat(map(page1.items().getFirst().get("properties"))).containsEntry("ouf:name","New");var page2=serving.history(first.objectId,1,page1.nextCursor(),null,open);assertThat(map(page2.items().getFirst().get("properties"))).containsEntry("ouf:name","Old");assertThat(db.sql("select count(*) from ouf_udp.property_conflict").query(Long.class).single()).isZero();}

  @Test void tenantMismatchUsesAntiEnumerationNotFound(){Fixture f=materialize("tenant-h","asset-2","Name","secret",null);db.sql("update ouf_udp.urban_object set tenant_id='other' where urban_object_id=:u").param("u",f.objectId).update();assertThatThrownBy(()->serving.current(f.objectId,open)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404").hasMessageNotContaining(f.objectId.toString());}

  @Test void lineageOmitsSourceAndRawReferencesWithoutSeparateCapabilities(){Fixture f=materialize("lineage-h","asset-3","Name","secret",null);List<Map<String,Object>> lineage=serving.lineage(f.objectId,"ouf:name",open);assertThat(lineage).hasSize(1);assertThat(lineage.getFirst()).containsKeys("handoffRef","lineageRef","contractRefs").doesNotContainKeys("sourceIdentity","rawObjectRef");}

  @Test void restrictedRelationshipIsOmittedWithoutDegreeOrCursorLeak(){Fixture target=materialize("target-h","target","Target","s",null);Fixture source=materialize("source-h","source","Source","s","Target");var profile=new UdpPorts.RelationshipProfile("policy://relation/1",List.of(new UdpPorts.RelationshipRule("ref","ouf:linkedTo","ouf:Asset","ouf:name","CANONICAL_KEY","QUARANTINE_RELATION","RESTRICTED",false)));relationship.materialize("source-h",source.objectId,source.payload,profile);var page=serving.relationships(source.objectId,10,null,open);assertThat(page.items()).isEmpty();assertThat(page.nextCursor()).isNull();assertThat(db.sql("select count(*) from ouf_udp.urban_relationship where target_object_id=:t").param("t",target.objectId).query(Long.class).single()).isOne();}

  @Test void searchRequiresIndexedExactTypeAndBoundedPage(){assertThatThrownBy(()->serving.search("*",10,null,open)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->serving.search("ouf:Asset",101,null,open)).hasMessageContaining("UDP_PAGE_SIZE_OUT_OF_RANGE");}

  private Fixture materialize(String handoff,String object,String name,String secret,String ref){Map<String,Object> payload=handoff(handoff,object,name,secret,ref);intake.accept(payload);var decision=resolution.resolve(handoff,payload,resolutionProfile);canonical.materialize(handoff,decision.targetUrbanObjectId(),payload,materialization);return new Fixture(decision.targetUrbanObjectId(),payload);}
  private static Map<String,Object> handoff(String id,String object,String name,String secret,String ref){Map<String,Object> content=new LinkedHashMap<>();content.put("identity",object);content.put("name",name);content.put("secret",secret);if(ref!=null)content.put("ref",ref);return new LinkedHashMap<>(Map.ofEntries(Map.entry("handoffId",id),Map.entry("ingestionRunId","run-1"),Map.entry("ingestionId","ing-"+id),Map.entry("sourceIdentity",new LinkedHashMap<>(Map.of("sourceId","registry","typeCode","ASSET","sourceObjectId",object,"observedAt","2026-09-13T00:00:00Z"))),Map.entry("operation","UPSERT"),Map.entry("canonicalPayload",content),Map.entry("rawObjectRef","raw://"+id),Map.entry("contractRefs",Map.of("sourceSchemaRef","schema://1","bundleRef","bundle://1","semanticPublicationSetRef","semantic://1","adapterProfileRef","adapter://1")),Map.entry("lineageId","lineage-"+id),Map.entry("contentHash","sha256:"+id),Map.entry("acquiredAt","2026-09-13T00:00:00Z"),Map.entry("changeRepresentation",Map.of("mode","FULL_SNAPSHOT"))));}
  private static String required(String n){String v=System.getenv(n);if(v==null)throw new IllegalStateException(n+" required");return v;}
  @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
  private record Fixture(UUID objectId,Map<String,Object> payload){}
}
