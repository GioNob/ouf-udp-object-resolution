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
  @Autowired HandoffIntakeService intake;@Autowired ObjectResolutionService resolution;@Autowired CanonicalMaterializer canonical;@Autowired SpatialMaterializer spatial;@Autowired JdbcClient db;@Autowired com.fasterxml.jackson.databind.ObjectMapper json;@Autowired GovernedServingService serving;@Autowired ResolutionRepository jobs;@Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  private final UdpPorts.MaterializationProfile properties=new UdpPorts.MaterializationProfile("policy://authority/1",List.of(new UdpPorts.PropertyRule("identity","ouf:id","string","OPEN",List.of())));
  private final UdpPorts.GeometryRule geometry=new UdpPorts.GeometryRule("geometry","EPSG:4326",4326,"postgis-3.5/no-repair","RESTRICTED");

  @BeforeEach void clean(){db.sql("truncate table ouf_udp.spatial_resolution_issue,ouf_udp.urban_geometry_current,ouf_udp.urban_geometry,ouf_udp.relationship_issue,ouf_udp.relationship_revision,ouf_udp.relationship_contribution,ouf_udp.urban_relationship,ouf_udp.property_conflict,ouf_udp.property_value,ouf_udp.materialization_observation,ouf_udp.property_contribution,ouf_udp.object_revision,ouf_udp.resolution_issue,ouf_udp.resolution_decision,ouf_udp.source_binding,ouf_udp.urban_object,ouf_udp.materialization_job,ouf_udp.handoff_event,ouf_udp.handoff_intake restart identity cascade").update();}

  @Test void intersectsCreatesVersionedEdgeWithCrsAndPredicateEvidence(){storeTarget("area-h","area-1",polygon(0,0,10,10));Fixture source=create("work-h","work-1","WORKSITE","ouf:Worksite",point(5,5),"EPSG:4326");var result=spatial.materialize("work-h",source.objectId,source.payload,profile("INTERSECTS"));assertThat(result).isEqualTo(new SpatialMaterializer.Result(true,1,0));String evidence=db.sql("select resolution_evidence::text from ouf_udp.relationship_revision").query(String.class).single();assertThat(evidence).contains("INTERSECTS","geometryRevisionRef");String geometryEvidence=db.sql("select evidence_json::text from ouf_udp.urban_geometry where urban_object_id=:u").param("u",source.objectId).query(String.class).single();assertThat(geometryEvidence).contains("EPSG:4326","postgis-3.5/no-repair");}

  @Test void missingOrMismatchedCrsNeverGetsAutoInterpreted(){Fixture missing=create("missing-crs","work-2","WORKSITE","ouf:Worksite",point(1,1),null);spatial.materialize("missing-crs",missing.objectId,missing.payload,profile("INTERSECTS"));assertThat(db.sql("select reason_code from ouf_udp.spatial_resolution_issue").query(String.class).single()).isEqualTo("SPATIAL_CRS_REQUIRED");assertThat(db.sql("select count(*) from ouf_udp.urban_geometry").query(Long.class).single()).isZero();}

  @Test void invalidGeometryIsQuarantinedWithoutSilentRepair(){Fixture source=create("invalid-h","work-3","WORKSITE","ouf:Worksite",bowTie(),"EPSG:4326");spatial.materialize("invalid-h",source.objectId,source.payload,profile("INTERSECTS"));assertThat(db.sql("select reason_code from ouf_udp.spatial_resolution_issue").query(String.class).single()).isEqualTo("SPATIAL_INVALID_GEOMETRY");assertThat(db.sql("select count(*) from ouf_udp.urban_geometry").query(Long.class).single()).isZero();}

  @Test void multipleSpatialMatchesOpenReviewWithoutArbitraryEdge(){storeTarget("area-a","area-a",polygon(0,0,10,10));storeTarget("area-b","area-b",polygon(4,4,12,12));Fixture source=create("multi-h","work-4","WORKSITE","ouf:Worksite",point(5,5),"EPSG:4326");spatial.materialize("multi-h",source.objectId,source.payload,profile("INTERSECTS"));assertThat(db.sql("select reason_code from ouf_udp.spatial_resolution_issue").query(String.class).single()).isEqualTo("SPATIAL_MULTIPLE_MATCHES");assertThat(db.sql("select jsonb_array_length(candidate_refs) from ouf_udp.spatial_resolution_issue").query(Integer.class).single()).isEqualTo(2);assertThat(db.sql("select count(*) from ouf_udp.urban_relationship").query(Long.class).single()).isZero();}

  @Test void canonicalGeometryOriginalAndProvenanceSurviveAndAreAuthorizedTogether(){
    Fixture source=create("municipal-h","camera-1","CAMERA","ouf:Camera",point(2,49),"EPSG:4326");
    var helper=new GovernedCrsTransformRuntimeTest();helper.db=db;helper.json=json;
    var rule=helper.rule(4326,32631,"XY","CONVERT",helper.forward(),helper.backward());
    var materializer=new SpatialMaterializer(db,json,new GovernedCrsTransform(db,json,32631,""));
    var profile=new UdpPorts.SpatialProfile("policy://municipal-crs/1",rule,List.of());
    assertThat(materializer.materialize("municipal-h",source.objectId,source.payload,profile).geometryStored()).isTrue();
    assertThat(materializer.materialize("municipal-h",source.objectId,source.payload,profile).geometryStored()).isTrue();
    assertThat(db.sql("select count(*) from ouf_udp.urban_geometry where urban_object_id=:u").param("u",source.objectId).query(Long.class).single()).isOne();
    var row=db.sql("select ST_SRID(canonical_geometry) canonical,ST_SRID(geometry) serving,source_geometry_json->>'crs' original,evidence_json::text evidence from ouf_udp.urban_geometry where urban_object_id=:u").param("u",source.objectId).query().singleRow();
    assertThat(row.get("canonical")).isEqualTo(32631);assertThat(row.get("serving")).isEqualTo(4326);assertThat(row.get("original")).isEqualTo("EPSG:4326");assertThat(String.valueOf(row.get("evidence"))).contains("test-operation","canonicalAxisOrder","sourceBounds");
    var allowed=new ServingAuthorizationContext("HUMAN_USER","operator","default",Set.of("urban.object.read","urban.geometry.read"),Set.of("OPEN","RESTRICTED"),"authz://geometry/1","geometry-1");
    assertThat(serving.current(source.objectId,allowed)).containsKeys("geometry","canonicalGeometry","geometryProvenance");
    var restricted=new ServingAuthorizationContext("AI_AGENT","agent","default",Set.of("urban.object.read","urban.geometry.read"),Set.of("OPEN"),"authz://geometry/2","geometry-2");
    assertThat(serving.current(source.objectId,restricted)).doesNotContainKeys("geometry","canonicalGeometry","geometryProvenance","geometryCrs");
  }

  @Test void publishedLoopRejectsBeforeCreatingAnObjectAndConvertsApprovedProfile(){
    var helper=new GovernedCrsTransformRuntimeTest();helper.db=db;helper.json=json;
    var geo=new SpatialMaterializer(db,json,new GovernedCrsTransform(db,json,32631,""));
    var config=org.mockito.Mockito.mock(PublishedRuntimeConfiguration.class);var gate=org.mockito.Mockito.mock(MaterializationReferenceGate.class);
    org.mockito.Mockito.when(gate.verify(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    var rp=new UdpPorts.ResolutionProfile("CANONICAL_KEY","1","policy://resolution/1","ouf:Camera","identity","identity");
    var rejected=new UdpPorts.SpatialProfile("policy://spatial/reject",helper.rule(4326,32631,"XY","REJECT",null,null),List.of());
    org.mockito.Mockito.when(config.resolve("bundle://1","CAMERA")).thenReturn(new PublishedRuntimeConfiguration.Profiles(Map.of(),rp,properties,rejected));
    var loop=new PublishedResolutionLoop(jobs,config,gate,resolution,canonical,geo,db,new org.springframework.transaction.support.TransactionTemplate(transactions));
    intake.accept(handoff("rejected-auto","camera-rejected","CAMERA",point(2,49),"EPSG:4326"));loop.tick();
    assertThat(db.sql("select state from ouf_udp.materialization_job where handoff_id='rejected-auto'").query(String.class).single()).isEqualTo("QUARANTINED");
    assertThat(db.sql("select reason_code from ouf_udp.spatial_resolution_issue where handoff_id='rejected-auto'").query(String.class).single()).isEqualTo("SPATIAL_CRS_REJECTED");
    assertThat(db.sql("select count(*) from ouf_udp.urban_object").query(Long.class).single()).isZero();
    var accepted=new UdpPorts.SpatialProfile("policy://spatial/convert",helper.rule(4326,32631,"XY","CONVERT",helper.forward(),helper.backward()),List.of());
    org.mockito.Mockito.when(config.resolve("bundle://1","CAMERA")).thenReturn(new PublishedRuntimeConfiguration.Profiles(Map.of(),rp,properties,accepted));
    intake.accept(handoff("converted-auto","camera-converted","CAMERA",point(2,49),"EPSG:4326"));loop.tick();
    assertThat(db.sql("select state from ouf_udp.materialization_job where handoff_id='converted-auto'").query(String.class).single()).isEqualTo("SUCCEEDED");
    assertThat(db.sql("select ST_SRID(canonical_geometry) from ouf_udp.urban_geometry where handoff_id='converted-auto'").query(Integer.class).single()).isEqualTo(32631);
    assertThat(db.sql("select count(*) from ouf_udp.object_revision").query(Long.class).single()).isOne();
  }

  @Test void divergentSourcesRequireReviewBeforeChangingCurrentGeometry(){
    Fixture first=create("authority-a","same-object","ASSET","ouf:Asset",polygon(0,0,1,1),"EPSG:4326");
    var profile=new UdpPorts.SpatialProfile("policy://geometry/1",geometry,List.of());
    spatial.materialize("authority-a",first.objectId,first.payload,profile);
    UUID before=db.sql("select geometry_revision_id from ouf_udp.urban_geometry_current where urban_object_id=:u").param("u",first.objectId).query(UUID.class).single();
    var next=handoff("authority-b","same-object","ASSET",polygon(0,0,2,2),"EPSG:4326");
    @SuppressWarnings("unchecked") var source=(Map<String,Object>)next.get("sourceIdentity");source.put("sourceId","survey");intake.accept(next);
    var authority=new UdpPorts.MaterializationProfile("policy://authority/tie",List.of(new UdpPorts.PropertyRule("geometry","geometry","geometry","RESTRICTED",List.of())));
    var transformed=spatial.prepare("authority-b",next,profile);
    assertThat(spatial.currentAction("authority-b",first.objectId,next,profile,authority,transformed)).isEqualTo("REVIEW_REQUIRED");
    assertThat(db.sql("select geometry_revision_id from ouf_udp.urban_geometry_current where urban_object_id=:u").param("u",first.objectId).query(UUID.class).single()).isEqualTo(before);
    assertThat(db.sql("select count(*) from ouf_udp.urban_geometry").query(Long.class).single()).isEqualTo(2);
    assertThat(db.sql("select reason_code from ouf_udp.spatial_resolution_issue").query(String.class).single()).isEqualTo("SPATIAL_AUTHORITY_CONFLICT");
    var explicit=new UdpPorts.MaterializationProfile("policy://authority/survey",List.of(new UdpPorts.PropertyRule("geometry","geometry","geometry","RESTRICTED",List.of("survey","spatial-source"))));
    assertThat(spatial.currentAction("authority-b",first.objectId,next,profile,explicit,transformed)).isEqualTo("ADVANCE");
  }

  @Test void humanGeometryChoiceResumesJobAndSurvivesReimportWithoutBecomingAGlobalRule(){
    for(boolean accept:List.of(false,true)){
      clean();
      var authority=new UdpPorts.MaterializationProfile("policy://authority/geometry-review",List.of(new UdpPorts.PropertyRule("identity","identity","string","OPEN",List.of()),new UdpPorts.PropertyRule("geometry","geometry","geometry","RESTRICTED",List.of())));
      var profile=new UdpPorts.SpatialProfile("policy://geometry/1",geometry,List.of());
      var rp=new UdpPorts.ResolutionProfile("CANONICAL_KEY","1","policy://resolution/1","ouf:Asset","identity","identity");
      var config=org.mockito.Mockito.mock(PublishedRuntimeConfiguration.class);var gate=org.mockito.Mockito.mock(MaterializationReferenceGate.class);
      org.mockito.Mockito.when(gate.verify(org.mockito.ArgumentMatchers.any())).thenReturn(true);
      org.mockito.Mockito.when(config.resolve("bundle://1","ASSET")).thenReturn(new PublishedRuntimeConfiguration.Profiles(Map.of(),rp,authority,profile));
      var loop=new PublishedResolutionLoop(jobs,config,gate,resolution,canonical,spatial,db,new org.springframework.transaction.support.TransactionTemplate(transactions));
      intake.accept(handoff("human-a","asset","ASSET",polygon(0,0,1,1),"EPSG:4326"));loop.tick();
      UUID object=db.sql("select urban_object_id from ouf_udp.urban_object").query(UUID.class).single();
      UUID before=db.sql("select geometry_revision_id from ouf_udp.urban_geometry_current").query(UUID.class).single();
      var next=handoff("human-b","asset","ASSET",polygon(0,0,2,2),"EPSG:4326");HandoffIntakeService.object(next,"sourceIdentity").put("sourceId","survey");intake.accept(next);loop.tick();
      assertThat(db.sql("select state from ouf_udp.materialization_job where handoff_id='human-b'").query(String.class).single()).isEqualTo("QUARANTINED");
      UUID issue=db.sql("select issue_id from ouf_udp.spatial_resolution_issue").query(UUID.class).single();
      UUID candidate=db.sql("select geometry_revision_id from ouf_udp.urban_geometry where handoff_id='human-b'").query(UUID.class).single();
      var caps=Set.of("authority.override","resolution.issue.read","urban.geometry.read");
      var actor=new TrustedHumanContext("HUMAN","operator","default",caps,"authz://review","review-1");
      var auth=new ServingAuthorizationContext("HUMAN","operator","default",caps,Set.of("OPEN","RESTRICTED"),"authz://review","review-1");
      var governance=new GeometryGovernanceService(db,json);
      assertThat(governance.review(issue,auth)).containsKeys("current","candidate","actions");
      var denied=new ServingAuthorizationContext("HUMAN","operator","default",caps,Set.of("OPEN"),"authz://review","review-1");
      assertThatThrownBy(()->governance.review(issue,denied)).hasMessageContaining("403");
      UUID chosen=accept?candidate:before;
      assertThatThrownBy(()->governance.decide(issue,UUID.randomUUID(),chosen,"inspected",actor,auth)).hasMessageContaining("409");
      var machine=new TrustedHumanContext("SERVICE_IDENTITY","operator","default",caps,"authz://review","review-1");
      assertThatThrownBy(()->governance.decide(issue,before,chosen,"inspected",machine,auth)).isInstanceOf(SecurityException.class);
      // Exercise the same transaction boundary as the Spring-managed API service.
      var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
      UUID decision=tx.execute(status->governance.decide(issue,before,chosen,"inspected",actor,auth));
      UUID retried=tx.execute(status->governance.decide(issue,before,chosen,"inspected",actor,auth));assertThat(retried).isEqualTo(decision);
      loop.tick();assertThat(db.sql("select state from ouf_udp.materialization_job where handoff_id='human-b'").query(String.class).single()).isEqualTo("SUCCEEDED");
      assertThat(db.sql("select geometry_revision_id from ouf_udp.urban_geometry_current").query(UUID.class).single()).isEqualTo(chosen);
      assertThat(db.sql("select canonical_payload->'geometry' = g.source_geometry_json from ouf_udp.urban_object_current_state c join ouf_udp.urban_geometry g on g.geometry_revision_id=:r where c.urban_object_id=:u").param("r",chosen).param("u",object).query(Boolean.class).single()).isTrue();
      for(var source:List.of("spatial-source","survey")){
        var reload=handoff("reload-"+source,"asset","ASSET",source.equals("survey")?polygon(0,0,2,2):polygon(0,0,1,1),"EPSG:4326");HandoffIntakeService.object(reload,"sourceIdentity").put("sourceId",source);intake.accept(reload);loop.tick();
      }
      assertThat(db.sql("select geometry_revision_id from ouf_udp.urban_geometry_current").query(UUID.class).single()).isEqualTo(chosen);
      assertThat(db.sql("select count(*) from ouf_udp.human_geometry_decision").query(Long.class).single()).isOne();
      assertThat(db.sql("select count(*) from ouf_udp.property_conflict").query(Long.class).single()).isZero();
      assertThatThrownBy(()->db.sql("delete from ouf_udp.human_geometry_decision").update()).hasStackTraceContaining("append-only");
      var changed=handoff("new-shape","asset","ASSET",polygon(0,0,3,3),"EPSG:4326");HandoffIntakeService.object(changed,"sourceIdentity").put("sourceId",accept?"spatial-source":"survey");intake.accept(changed);loop.tick();
      assertThat(db.sql("select state from ouf_udp.materialization_job where handoff_id='new-shape'").query(String.class).single()).isEqualTo("QUARANTINED");
      var chosenUpdate=handoff("chosen-source-new-shape","asset","ASSET",polygon(0,0,4,4),"EPSG:4326");var changedIdentity=HandoffIntakeService.object(chosenUpdate,"sourceIdentity");changedIdentity.put("sourceId",accept?"survey":"spatial-source");changedIdentity.put("observedAt","2026-09-15T00:00:00Z");intake.accept(chosenUpdate);loop.tick();
      assertThat(db.sql("select state from ouf_udp.materialization_job where handoff_id='chosen-source-new-shape'").query(String.class).single()).isEqualTo("QUARANTINED");
      assertThat(db.sql("select geometry_revision_id from ouf_udp.urban_geometry_current").query(UUID.class).single()).isEqualTo(chosen);

    }
  }

  @Test void rolesRemainSeparateAndLateGeometryCannotReplaceCurrent(){
    Fixture first=create("roles-a","asset","ASSET","ouf:Asset",polygon(0,0,1,1),"EPSG:4326");
    var primary=new UdpPorts.SpatialProfile("policy://geometry/1",geometry,List.of());
    spatial.materialize("roles-a",first.objectId,first.payload,primary);
    UUID before=db.sql("select geometry_revision_id from ouf_udp.urban_geometry_current").query(UUID.class).single();
    var secondary=new UdpPorts.SpatialProfile("policy://geometry/secondary",new UdpPorts.GeometryRule("geometry","EPSG:4326",4326,"1","RESTRICTED",null,"FOOTPRINT"),List.of());
    var other=handoff("roles-b","asset","ASSET",polygon(0,0,2,2),"EPSG:4326");intake.accept(other);
    spatial.materialize("roles-b",first.objectId,other,secondary);
    assertThat(db.sql("select count(*) from ouf_udp.urban_geometry_role_current").query(Long.class).single()).isEqualTo(2);
    assertThat(db.sql("select geometry_revision_id from ouf_udp.urban_geometry_current").query(UUID.class).single()).isEqualTo(before);
    var authority=new UdpPorts.MaterializationProfile("policy://authority/1",List.of(new UdpPorts.PropertyRule("geometry","geometry","geometry","RESTRICTED",List.of())));
    var late=handoff("late","asset","ASSET",polygon(0,0,3,3),"EPSG:4326");HandoffIntakeService.object(late,"sourceIdentity").put("observedAt","2026-09-11T00:00:00Z");intake.accept(late);
    assertThat(spatial.currentAction("late",first.objectId,late,primary,authority,spatial.prepare("late",late,primary))).isEqualTo("HISTORICAL_ONLY");
    assertThat(db.sql("select geometry_revision_id from ouf_udp.urban_geometry_current").query(UUID.class).single()).isEqualTo(before);
    var repeated=handoff("repeat-shape","asset","ASSET",polygon(0,0,1,1),"EPSG:4326");HandoffIntakeService.object(repeated,"sourceIdentity").put("observedAt","2026-09-14T00:00:00Z");intake.accept(repeated);
    assertThat(spatial.currentAction("repeat-shape",first.objectId,repeated,primary,authority,spatial.prepare("repeat-shape",repeated,primary))).isEqualTo("ADVANCE");spatial.materialize("repeat-shape",first.objectId,repeated,primary);
    var intermediate=handoff("intermediate","asset","ASSET",polygon(0,0,5,5),"EPSG:4326");HandoffIntakeService.object(intermediate,"sourceIdentity").put("observedAt","2026-09-13T00:00:00Z");intake.accept(intermediate);
    assertThat(spatial.currentAction("intermediate",first.objectId,intermediate,primary,authority,spatial.prepare("intermediate",intermediate,primary))).isEqualTo("HISTORICAL_ONLY");
    var sameTime=handoff("same-time","asset","ASSET",polygon(0,0,4,4),"EPSG:4326");HandoffIntakeService.object(sameTime,"sourceIdentity").put("observedAt","2026-09-14T00:00:00Z");intake.accept(sameTime);
    assertThat(spatial.currentAction("same-time",first.objectId,sameTime,primary,authority,spatial.prepare("same-time",sameTime,primary))).isEqualTo("REVIEW_REQUIRED");
    var auth=new ServingAuthorizationContext("HUMAN","reader","default",Set.of("urban.object.read","urban.geometry.read"),Set.of("OPEN","RESTRICTED"),"authz://roles","roles");
    assertThat((List<?>)serving.current(first.objectId,auth).get("geometries")).hasSize(2);
    var denied=new ServingAuthorizationContext("HUMAN","reader","default",Set.of("urban.object.read","urban.geometry.read"),Set.of("OPEN"),"authz://roles","roles");
    assertThat(serving.current(first.objectId,denied)).doesNotContainKeys("geometry","geometries");
  }

  @Test void geometryValidityIsPreservedAndNoncurrentIntervalsDoNotAdvance(){
    Fixture first=create("valid-a","asset","ASSET","ouf:Asset",polygon(0,0,1,1),"EPSG:4326");
    var profile=new UdpPorts.SpatialProfile("policy://geometry/1",geometry,List.of());spatial.materialize("valid-a",first.objectId,first.payload,profile);
    var authority=new UdpPorts.MaterializationProfile("policy://authority/1",List.of(new UdpPorts.PropertyRule("geometry","geometry","geometry","RESTRICTED",List.of())));
    var expired=handoff("expired","asset","ASSET",polygon(0,0,2,2),"EPSG:4326");var identity=HandoffIntakeService.object(expired,"sourceIdentity");identity.put("validFrom","2020-01-01T00:00:00Z");identity.put("validTo","2021-01-01T00:00:00Z");intake.accept(expired);
    assertThat(spatial.currentAction("expired",first.objectId,expired,profile,authority,spatial.prepare("expired",expired,profile))).isEqualTo("HISTORICAL_ONLY");
    assertThat(db.sql("select valid_to from ouf_udp.urban_geometry where handoff_id='expired'").query(java.time.OffsetDateTime.class).single()).isEqualTo(java.time.OffsetDateTime.parse("2021-01-01T00:00:00Z"));
    var invalid=handoff("invalid-time","asset","ASSET",polygon(0,0,2,2),"EPSG:4326");HandoffIntakeService.object(invalid,"sourceIdentity").put("validFrom","2022-01-01T00:00:00Z");HandoffIntakeService.object(invalid,"sourceIdentity").put("validTo","2021-01-01T00:00:00Z");intake.accept(invalid);
    assertThat(spatial.prepare("invalid-time",invalid,profile).status()).isEqualTo("SPATIAL_POLICY_INVALID");
  }

  @Test void futureGeometryIsDurablyScheduledWithoutBecomingCurrent(){
    var profile=new UdpPorts.SpatialProfile("policy://geometry/1",geometry,List.of());
    var rp=new UdpPorts.ResolutionProfile("CANONICAL_KEY","1","policy://resolution/1","ouf:Asset","identity","identity");
    var config=org.mockito.Mockito.mock(PublishedRuntimeConfiguration.class);var gate=org.mockito.Mockito.mock(MaterializationReferenceGate.class);
    org.mockito.Mockito.when(gate.verify(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    org.mockito.Mockito.when(config.resolve("bundle://1","ASSET")).thenReturn(new PublishedRuntimeConfiguration.Profiles(Map.of(),rp,properties,profile));
    var loop=new PublishedResolutionLoop(jobs,config,gate,resolution,canonical,spatial,db,new org.springframework.transaction.support.TransactionTemplate(transactions));
    var future=handoff("future","asset","ASSET",polygon(0,0,1,1),"EPSG:4326");var validFrom=java.time.OffsetDateTime.now().plusDays(1);HandoffIntakeService.object(future,"sourceIdentity").put("validFrom",validFrom.toString());intake.accept(future);loop.tick();
    assertThat(db.sql("select state from ouf_udp.materialization_job").query(String.class).single()).isEqualTo("PAUSED");
    assertThat(db.sql("select safe_failure_code from ouf_udp.materialization_job").query(String.class).single()).isEqualTo("UDP_GEOMETRY_NOT_YET_VALID");
    assertThat(db.sql("select count(*) from ouf_udp.urban_geometry_current").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from ouf_udp.urban_geometry").query(Long.class).single()).isOne();
    assertThat(jobs.claim("early",java.time.Duration.ofMinutes(1))).isEmpty();
  }

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
