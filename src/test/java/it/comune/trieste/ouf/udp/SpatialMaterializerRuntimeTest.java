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
class SpatialMaterializerRuntimeTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_UDP_DB_URL"));r.add("spring.datasource.username",()->required("OUF_UDP_DB_USER"));r.add("spring.datasource.password",()->required("OUF_UDP_DB_PASSWORD"));}
  @Autowired HandoffIntakeService intake;@Autowired ObjectResolutionService resolution;@Autowired CanonicalMaterializer canonical;@Autowired SpatialMaterializer spatial;@Autowired JdbcClient db;
  private final UdpPorts.MaterializationProfile properties=new UdpPorts.MaterializationProfile("policy://authority/1",List.of(new UdpPorts.PropertyRule("identity","ouf:id","string","OPEN",List.of())));
  private final UdpPorts.GeometryRule geometry=new UdpPorts.GeometryRule("geometry","EPSG:4326",4326,"postgis-3.5/no-repair","RESTRICTED");

  @BeforeEach void clean(){db.sql("truncate table ouf_udp.spatial_resolution_issue,ouf_udp.urban_geometry_current,ouf_udp.urban_geometry,ouf_udp.relationship_issue,ouf_udp.relationship_revision,ouf_udp.relationship_contribution,ouf_udp.urban_relationship,ouf_udp.property_conflict,ouf_udp.property_value,ouf_udp.materialization_observation,ouf_udp.property_contribution,ouf_udp.object_revision,ouf_udp.resolution_issue,ouf_udp.resolution_decision,ouf_udp.source_binding,ouf_udp.urban_object,ouf_udp.materialization_job,ouf_udp.handoff_event,ouf_udp.handoff_intake restart identity cascade").update();}

  @Test void intersectsCreatesVersionedEdgeWithCrsAndPredicateEvidence(){storeTarget("area-h","area-1",polygon(0,0,10,10));Fixture source=create("work-h","work-1","WORKSITE","ouf:Worksite",point(5,5),"EPSG:4326");var result=spatial.materialize("work-h",source.objectId,source.payload,profile("INTERSECTS"));assertThat(result).isEqualTo(new SpatialMaterializer.Result(true,1,0));String evidence=db.sql("select resolution_evidence::text from ouf_udp.relationship_revision").query(String.class).single();assertThat(evidence).contains("INTERSECTS","geometryRevisionRef");String geometryEvidence=db.sql("select evidence_json::text from ouf_udp.urban_geometry where urban_object_id=:u").param("u",source.objectId).query(String.class).single();assertThat(geometryEvidence).contains("EPSG:4326","postgis-3.5/no-repair");}

  @Test void missingOrMismatchedCrsNeverGetsAutoInterpreted(){Fixture missing=create("missing-crs","work-2","WORKSITE","ouf:Worksite",point(1,1),null);spatial.materialize("missing-crs",missing.objectId,missing.payload,profile("INTERSECTS"));assertThat(db.sql("select reason_code from ouf_udp.spatial_resolution_issue").query(String.class).single()).isEqualTo("SPATIAL_CRS_REQUIRED");assertThat(db.sql("select count(*) from ouf_udp.urban_geometry").query(Long.class).single()).isZero();}

  @Test void invalidGeometryIsQuarantinedWithoutSilentRepair(){Fixture source=create("invalid-h","work-3","WORKSITE","ouf:Worksite",bowTie(),"EPSG:4326");spatial.materialize("invalid-h",source.objectId,source.payload,profile("INTERSECTS"));assertThat(db.sql("select reason_code from ouf_udp.spatial_resolution_issue").query(String.class).single()).isEqualTo("SPATIAL_INVALID_GEOMETRY");assertThat(db.sql("select count(*) from ouf_udp.urban_geometry").query(Long.class).single()).isZero();}

  @Test void multipleSpatialMatchesOpenReviewWithoutArbitraryEdge(){storeTarget("area-a","area-a",polygon(0,0,10,10));storeTarget("area-b","area-b",polygon(4,4,12,12));Fixture source=create("multi-h","work-4","WORKSITE","ouf:Worksite",point(5,5),"EPSG:4326");spatial.materialize("multi-h",source.objectId,source.payload,profile("INTERSECTS"));assertThat(db.sql("select reason_code from ouf_udp.spatial_resolution_issue").query(String.class).single()).isEqualTo("SPATIAL_MULTIPLE_MATCHES");assertThat(db.sql("select jsonb_array_length(candidate_refs) from ouf_udp.spatial_resolution_issue").query(Integer.class).single()).isEqualTo(2);assertThat(db.sql("select count(*) from ouf_udp.urban_relationship").query(Long.class).single()).isZero();}

  private void storeTarget(String handoff,String object,Object geometryValue){Fixture f=create(handoff,object,"AREA","ouf:Area",geometryValue,"EPSG:4326");spatial.materialize(handoff,f.objectId,f.payload,new UdpPorts.SpatialProfile("policy://spatial/1",geometry,List.of()));}
  private UdpPorts.SpatialProfile profile(String predicate){return new UdpPorts.SpatialProfile("policy://spatial/1",geometry,List.of(new UdpPorts.SpatialRelationshipRule("ouf:insideArea","ouf:Area",predicate,"QUARANTINE_RELATION",null,null,null,"RESTRICTED",false)));}
  private Fixture create(String handoff,String object,String type,String canonicalType,Object geometryValue,String crs){Map<String,Object> payload=handoff(handoff,object,type,geometryValue,crs);intake.accept(payload);var rp=new UdpPorts.ResolutionProfile("CANONICAL_KEY","1","policy://resolution/1",canonicalType,"identity","identity");UUID id=resolution.resolve(handoff,payload,rp).targetUrbanObjectId();canonical.materialize(handoff,id,payload,properties);return new Fixture(id,payload);}
  private static Map<String,Object> handoff(String id,String object,String type,Object geometryValue,String crs){Map<String,Object> envelope=new LinkedHashMap<>();if(crs!=null)envelope.put("crs",crs);envelope.put("geoJson",geometryValue);Map<String,Object> content=new LinkedHashMap<>();content.put("identity",object);content.put("geometry",envelope);return new LinkedHashMap<>(Map.ofEntries(Map.entry("handoffId",id),Map.entry("ingestionRunId","run-1"),Map.entry("ingestionId","ing-"+id),Map.entry("sourceIdentity",new LinkedHashMap<>(Map.of("sourceId","spatial-source","typeCode",type,"sourceObjectId",object,"observedAt","2026-09-12T00:00:00Z"))),Map.entry("operation","UPSERT"),Map.entry("canonicalPayload",content),Map.entry("rawObjectRef","raw://"+id),Map.entry("contractRefs",Map.of("sourceSchemaRef","schema://1","bundleRef","bundle://1","semanticPublicationSetRef","semantic://1","adapterProfileRef","adapter://1","relationshipResolutionStrategyRefs",List.of("spatial://1"))),Map.entry("lineageId","lineage-"+id),Map.entry("contentHash","sha256:"+id),Map.entry("acquiredAt","2026-09-12T00:00:00Z"),Map.entry("changeRepresentation",Map.of("mode","FULL_SNAPSHOT"))));}
  private static Map<String,Object> point(double x,double y){return Map.of("type","Point","coordinates",List.of(x,y));}
  private static Map<String,Object> polygon(double x1,double y1,double x2,double y2){return Map.of("type","Polygon","coordinates",List.of(List.of(List.of(x1,y1),List.of(x2,y1),List.of(x2,y2),List.of(x1,y2),List.of(x1,y1))));}
  private static Map<String,Object> bowTie(){return Map.of("type","Polygon","coordinates",List.of(List.of(List.of(0,0),List.of(2,2),List.of(0,2),List.of(2,0),List.of(0,0))));}
  private static String required(String n){String v=System.getenv(n);if(v==null)throw new IllegalStateException(n+" required");return v;}
  private record Fixture(UUID objectId,Map<String,Object> payload){}
}
