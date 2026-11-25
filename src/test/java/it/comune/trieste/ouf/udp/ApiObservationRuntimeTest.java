package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Real intake, claims, projections and serving. The two publication reads are fixtures. */
@SpringBootTest(properties="ouf.udp.lake.tenant-id=default")
class ApiObservationRuntimeTest {
  @Autowired JdbcClient db;
  @Autowired HandoffIntakeService intake;
  @Autowired ResolutionRepository jobs;
  @Autowired ObjectResolutionService resolver;
  @Autowired CanonicalMaterializer canonical;
  @Autowired SpatialMaterializer spatial;
  @Autowired RelationshipReconciliation relationships;
  @Autowired TransactionTemplate tx;
  @Autowired GovernedServingService serving;
  @Autowired GovernedIdentityResolutionService governed;
  @Autowired GovernedIdentityIndexBackfill backfill;
  private PublishedResolutionLoop loop;
  private PublishedRuntimeConfiguration config;
  private MaterializationReferenceGate gate;
  private final String objectKey=" road ";
  private int delivery;
  private final ServingAuthorizationContext reader=new ServingAuthorizationContext("HUMAN","reader","default",
      Set.of("urban.object.read","urban.object.history.read","urban.object.search"),Set.of("OPEN"),"authz://test","read");

  @BeforeEach void setup(){
    db.sql("truncate ouf_udp.handoff_intake,ouf_udp.urban_object restart identity cascade").update();
    gate=mock(MaterializationReferenceGate.class);
    when(gate.verify(any())).thenReturn(true);
    config=mock(PublishedRuntimeConfiguration.class);
    var identity=new UdpPorts.ResolutionProfile("CANONICAL_KEY","1","policy://test","Road","code","code");
    var order=List.of("registry","survey");
    var name=new UdpPorts.PropertyRule("name","name","string","OPEN",order);
    var surface=new UdpPorts.PropertyRule("surface","surface","number","OPEN",order);
    when(config.resolveForSource(eq("bundle://road/1"),eq("ROAD"),anyString())).thenReturn(new PublishedRuntimeConfiguration.Profiles(Map.of(),identity,
        new UdpPorts.MaterializationProfile("policy://road",List.of(name,surface)),null));
    when(config.resolveForSource(eq("bundle://road/2"),eq("ROAD"),anyString())).thenReturn(new PublishedRuntimeConfiguration.Profiles(Map.of(),identity,
        new UdpPorts.MaterializationProfile("policy://road",List.of(name)),null));
    loop=new PublishedResolutionLoop(jobs,config,gate,resolver,canonical,spatial,db,tx,relationships);
    org.springframework.test.util.ReflectionTestUtils.setField(loop,"tenant","default");
  }

  private Map<String,Object> envelope(String source,String kind,int rev,Long generation,boolean delete,
      String rendering,String digest,int day,int bundle){
    String id="obs-"+ ++delivery;
    var row=CanonicalMaterializerRuntimeTest.handoff(id,source,objectKey,"R",source+"-"+rev,rev);
    var identity=HandoffIntakeService.object(row,"sourceIdentity");
    String time="2026-10-%02dT00:00:00Z".formatted(day);
    identity.put("sourceRevision",String.valueOf(rev));identity.put("observedAt",time);
    var change=new HashMap<String,Object>();change.put("mode","FULL_SNAPSHOT");change.put("eventSequence",generation);
    row.put("changeRepresentation",change);
    var obs=new HashMap<String,Object>();
    obs.put("kind",kind);obs.put("schema","ouf.api-observation.v1");obs.put("epoch","first");
    obs.put("generation",generation);obs.put("snapshotAt",generation==null?null:time);
    obs.put("renderedSchemaVersion",rendering);obs.put("itemSha256",digest.repeat(64));
    row.put("apiObservation",obs);
    if(delete){row.put("operation","TOMBSTONE");row.remove("canonicalPayload");}
    var refs=new HashMap<>(HandoffIntakeService.object(row,"contractRefs"));
    refs.put("bundleRef","bundle://road/"+bundle);row.put("contractRefs",refs);
    return row;
  }
  private UUID apply(Map<String,Object> row,String expected){
    String id=(String)row.get("handoffId");
    var receipt=intake.accept(row);
    assertThat(receipt.durable()).isTrue();
    assertThat(intake.handoff(id)).doesNotContainKey("outcome");
    loop.tick();
    assertThat(intake.handoff(id)).as("handoff %s",id).containsEntry("outcome",expected);
    assertThat(intake.accept(row).duplicate()).isTrue();
    return db.sql("select urban_object_id from ouf_udp.api_handoff_outcome where handoff_id=:id")
        .param("id",id).query((r,n)->r.getObject(1,UUID.class)).list().getFirst();
  }
  private UUID event(String source,int rev,boolean delete,String expected){
    return apply(envelope(source,"EVENT",rev,null,delete,"v1","a",rev,1),expected);
  }
  @Test void r1ToR8AndR13_preserveEvidenceAndScopeConflict(){
    UUID id=event("registry",2,false,"APPLIED"); // R1
    assertThat(event("registry",2,false,"DUPLICATE")).isEqualTo(id); // R2
    apply(envelope("registry","EVENT",2,null,false,"v2","b",2,2),"DUPLICATE_REPUBLISHED"); // R3
    event("registry",1,false,"SUPERSEDED"); // R5, retained even though never current
    assertThat(event("registry",3,false,"APPLIED")).isEqualTo(id); // R6
    event("registry",4,true,"RETIRED"); // R7
    event("registry",5,true,"DUPLICATE"); // R13 advances the floor
    assertThat(db.sql("select last_revision from ouf_udp.api_source_object").query(Long.class).single()).isEqualTo(5);
    assertThat(event("registry",6,false,"REACTIVATED")).isEqualTo(id); // R8
    apply(envelope("registry","EVENT",1,null,false,"v1","b",1,1),"CONFLICT"); // R4 before R5
    assertThat(db.sql("select conflict_with from ouf_udp.api_handoff_outcome where outcome='CONFLICT'").query(String.class).single()).isEqualTo("obs-4");
    assertThat(db.sql("select count(*) from ouf_udp.resolution_decision").query(Integer.class).single()).isEqualTo(3);
    var blocked=envelope("registry","EVENT",7,null,false,"v1","a",7,1);
    intake.accept(blocked);loop.tick();
    assertThat(intake.handoff((String)blocked.get("handoffId"))).doesNotContainKey("outcome");
    assertThat(event("survey",1,false,"APPLIED")).isEqualTo(id);
    var page=intake.outcomes("default","registry","run-1",0,2);
    assertThat(page).isEqualTo(intake.outcomes("default","registry","run-1",0,2)).containsEntry("hasMore",true);
    assertThat(intake.outcomes("other","registry","run-1",0,2).get("items")).isEqualTo(List.of());
  }
  @Test void r9ThroughR12_snapshotEvidenceAndStrictCut(){
    UUID id=event("registry",1,false,"APPLIED");
    apply(envelope("registry","PRESENCE",1,1L,false,"v1","a",2,1),"SEEN"); // R9
    apply(envelope("registry","ABSENCE",1,2L,true,"v1","b",3,1),"RETIRED"); // R10
    assertThat(apply(envelope("registry","PRESENCE",1,3L,false,"v1","a",4,1),"REACTIVATED")).isEqualTo(id); // R11
    apply(envelope("registry","ABSENCE",1,4L,true,"v1","c",5,1),"RETIRED");
    apply(envelope("registry","EVENT",2,null,false,"v1","d",5,1),"SUPERSEDED"); // R12 equality
    assertThat(apply(envelope("registry","EVENT",3,null,false,"v1","e",6,1),"REACTIVATED")).isEqualTo(id);
  }
  @Test void r14FirstSnapshotDeleteCreatesOnlyAnOrderingFloor(){
    assertThat(apply(envelope("registry","SNAPSHOT_DELETE",4,1L,true,"v1","a",5,1),"RETIRED")).isNull();
    assertThat(event("registry",2,false,"SUPERSEDED")).isNull();
    assertThat(db.sql("select count(*) from ouf_udp.urban_object").query(Integer.class).single()).isZero();
    assertThat(event("registry",5,false,"APPLIED")).isNotNull();
  }
  @Test void twoSourcesRetirementAndMappingChangeAreVisibleThroughServing(){
    UUID id=event("registry",1,false,"APPLIED");
    assertThat(event("survey",2,false,"APPLIED")).isEqualTo(id);
    apply(envelope("registry","EVENT",3,null,false,"v1","b",3,2),"APPLIED");
    assertThat(serving.current(id,reader).get("properties")).isEqualTo(Map.of("name","registry-3","surface",2));
    event("survey",4,true,"RETIRED");
    assertThat(serving.current(id,reader).get("properties")).isEqualTo(Map.of("name","registry-3"));
    event("registry",5,true,"RETIRED");
    assertThat(serving.current(id,reader)).containsEntry("status","RETIRED");
    assertThat(serving.search("Road",10,null,reader).items()).isEmpty();
    assertThat(serving.history(id,20,null,null,reader).items().getFirst()).containsEntry("status","RETIRED");
    assertThat(serving.history(id,20,null,null,reader).items()).anyMatch(
        row->Map.of("name","registry-1","surface",1).equals(row.get("properties")));
    assertThat(event("registry",6,false,"REACTIVATED")).isEqualTo(id);
    assertThat(serving.search("Road",10,null,reader).items()).hasSize(1);
    assertThat(db.sql("select distinct source_object_id from ouf_udp.source_binding").query(String.class).single()).isEqualTo(objectKey);
  }
  @Test void geometryAndEdgesFallBackToTheRemainingSource(){
    var marker=envelope("marker","EVENT",1,null,false,"v1","a",1,1);
    marker.put("canonicalPayload",Map.of("code","M","name","target"));
    apply(marker,"APPLIED");
    var order=List.of("registry","survey");
    var materialization=new UdpPorts.MaterializationProfile("policy://spatial-test",List.of(
        new UdpPorts.PropertyRule("geometry","shape","json","OPEN",order)));
    var mapping=new PublishedRuntimeConfiguration.Profiles(Map.of(),
        new UdpPorts.ResolutionProfile("CANONICAL_KEY","1","policy://test","Road","code","code"),materialization,
        new UdpPorts.SpatialProfile("policy://test",new UdpPorts.GeometryRule("geometry","EPSG:4326",4326,"test","OPEN"),List.of()),
        new UdpPorts.RelationshipProfile("policy://edges",List.of(new UdpPorts.RelationshipRule(
            "link","locatedOn","Road","name","CANONICAL_KEY","QUARANTINE_RELATION","OPEN",false))));
    when(config.resolveForSource(eq("bundle://road/3"),eq("ROAD"),anyString())).thenReturn(mapping);
    for(String source:order){
      var row=envelope(source,"EVENT",1,null,false,"v1","a",1,3);
      var point=Map.of("type","Point","coordinates",List.of(source.equals("registry")?12:14,45));
      row.put("canonicalPayload",Map.of("code","R","geometry",Map.of("crs","EPSG:4326","geoJson",point),"link","target"));
      apply(row,"APPLIED");
    }
    String x="select ST_X(g.geometry) from ouf_udp.urban_geometry_current c join ouf_udp.urban_geometry g using(geometry_revision_id)";
    assertThat(db.sql(x).query(Double.class).single()).isEqualTo(12d);
    apply(envelope("registry","EVENT",2,null,true,"v1","b",2,3),"RETIRED");
    assertThat(db.sql(x).query(Double.class).single()).isEqualTo(14d);
    assertThat(db.sql("select status from ouf_udp.urban_relationship").query(String.class).single()).isEqualTo("ACTIVE");
    apply(envelope("survey","EVENT",2,null,true,"v1","b",2,3),"RETIRED");
    assertThat(db.sql(x).query(Double.class).list()).isEmpty();
    assertThat(db.sql("select status from ouf_udp.urban_relationship").query(String.class).single()).isEqualTo("SUPERSEDED");
    assertThat(db.sql("select count(*) from ouf_udp.urban_geometry").query(Integer.class).single()).isEqualTo(2);
  }

  @Test void failureAfterMaterializationRollsBackIdentityAndProgress(){
    var gate=mock(MaterializationReferenceGate.class);
    when(gate.verify(any())).thenReturn(true);
    var edges=mock(RelationshipReconciliation.class);
    doThrow(new IllegalStateException("UDP_TEST_FAILURE")).when(edges).accept(anyString(),any(),anyMap(),any());
    loop=new PublishedResolutionLoop(jobs,config,gate,resolver,canonical,spatial,db,tx,edges);
    org.springframework.test.util.ReflectionTestUtils.setField(loop,"tenant","default");
    var row=envelope("registry","EVENT",1,null,false,"v1","a",1,1);
    intake.accept(row);loop.tick();
    assertThat(intake.handoff((String)row.get("handoffId"))).containsEntry("state","FAILED").doesNotContainKey("outcome");
    for(String table:List.of("urban_object","source_binding","api_source_object","property_contribution","object_revision","api_handoff_outcome"))
      assertThat(db.sql("select count(*) from ouf_udp."+table).query(Integer.class).single()).as(table).isZero();
  }

  @Test void invalidApiInputsFailBeforeAdmission(){
    var row=envelope("registry","EVENT",1,null,true,"v1","a",1,1);
    row.put("canonicalPayload",Map.of());
    assertThatThrownBy(()->intake.accept(row)).isInstanceOf(FrozenContractValidator.ContractViolation.class);
    row.remove("canonicalPayload");
    var source=HandoffIntakeService.object(row,"sourceIdentity");
    source.put("sourceRevision","9223372036854775808");
    assertThatThrownBy(()->intake.accept(row)).isInstanceOf(FrozenContractValidator.ContractViolation.class);
    source.remove("sourceRevision");
    assertThatThrownBy(()->intake.accept(row)).isInstanceOf(FrozenContractValidator.ContractViolation.class);
    assertThat(db.sql("select count(*) from ouf_udp.handoff_intake").query(Integer.class).single()).isZero();
  }

  @Test void propertyRemovedFromTheMappingWithoutOtherSupportMovesToHistory(){
    UUID id=event("registry",1,false,"APPLIED");
    assertThat(serving.current(id,reader).get("properties")).isEqualTo(Map.of("name","registry-1","surface",1));
    // Bundle 2 no longer maps surface: the first APPLIED revision under it closes the value (D-093 p.7).
    apply(envelope("registry","EVENT",2,null,false,"v1","b",2,2),"APPLIED");
    assertThat(serving.current(id,reader).get("properties")).isEqualTo(Map.of("name","registry-2"));
    assertThat(serving.history(id,20,null,null,reader).items()).anyMatch(
        row->Map.of("name","registry-1","surface",1).equals(row.get("properties")));
    assertThat(db.sql("select count(*) from ouf_udp.property_bitemporal_interval where urban_object_id=:u and property_iri='surface' and system_to is null")
        .param("u",id).query(Integer.class).single()).isZero();
  }

  /** The candidate predicate of the V39 claim of the supported predecessor (256a0ef), verbatim. */
  private static final String V39_CLAIM="with candidate as (select job_id from ouf_udp.materialization_job where state='READY' or (state='RUNNING' and lease_until<transaction_timestamp()) or (state='PAUSED' and next_integrity_check_at<=transaction_timestamp()) order by created_at for update skip locked limit 1) update ouf_udp.materialization_job j set state='RUNNING',state_version=state_version+1,claimed_by='v39',lease_until=transaction_timestamp()+interval '2 minutes',attempts=attempts+1,updated_at=transaction_timestamp() from candidate c where j.job_id=c.job_id returning j.handoff_id";
  @Test void d080PredecessorWorkersNeverClaimApiJobs(){
    var row=envelope("registry","EVENT",1,null,false,"v1","a",1,1);
    String id=(String)row.get("handoffId");
    intake.accept(row);
    String job="select state||':'||(next_integrity_check_at is null)||':'||(api_dispatch_at is not null)||':'||api_semantics from ouf_udp.materialization_job where handoff_id=:h";
    assertThat(db.sql(job).param("h",id).query(String.class).single()).isEqualTo("PAUSED:true:true:true");
    assertThat(db.sql(V39_CLAIM).query(String.class).list()).isEmpty();
    assertThat(jobs.claim("legacy",java.time.Duration.ofMinutes(2))).isEmpty(); // candidate ResolutionWorker
    var claim=jobs.claimPublished("candidate",java.time.Duration.ofMinutes(2)).orElseThrow();
    assertThat(claim.handoffId()).isEqualTo(id);
    // A RUNNING API job keeps an infinite V39 lease; its real lease is api_dispatch_at.
    assertThat(db.sql("select lease_until='infinity'::timestamptz from ouf_udp.materialization_job where handoff_id=:h").param("h",id).query(Boolean.class).single()).isTrue();
    db.sql("update ouf_udp.materialization_job set api_dispatch_at=transaction_timestamp()-interval '1 second' where handoff_id=:h").param("h",id).update();
    assertThat(db.sql(V39_CLAIM).query(String.class).list()).isEmpty();
    assertThat(jobs.claimPublished("candidate-2",java.time.Duration.ofMinutes(2)).orElseThrow().handoffId()).isEqualTo(id);
    // File handoffs keep the V39 semantics.
    var file=CanonicalMaterializerRuntimeTest.handoff("file-1","registry","file-object","F","file",1);
    intake.accept(file);
    assertThat(db.sql(V39_CLAIM).query(String.class).list()).containsExactly("file-1");
  }
  @Test void d080ApiHandoffAdmittedByThePredecessorIsAdoptedByTheCandidate(){
    var row=envelope("registry","EVENT",1,null,false,"v1","a",1,1);
    String id=(String)row.get("handoffId");
    intake.accept(row);
    // V39 intake does not know api_tenant_id; the V40 trigger still fences the job.
    db.sql("update ouf_udp.materialization_job set api_tenant_id=null where handoff_id=:h").param("h",id).update();
    assertThat(intake.handoff(id)).doesNotContainKey("outcome");
    assertThat(intake.accept(row).duplicate()).isTrue();
    loop.tick();
    assertThat(intake.handoff(id)).containsEntry("outcome","APPLIED");
    assertThat(db.sql("select api_tenant_id from ouf_udp.materialization_job where handoff_id=:h").param("h",id).query(String.class).single()).isEqualTo("default");
  }
  @Test void outcomePagesAreOrderedIdempotentAndScoped(){
    for(int rev=1;rev<=3;rev++)event("registry",rev,false,"APPLIED");
    var first=intake.outcomes("default","registry","run-1",0,2);
    assertThat(first).containsEntry("schema","ouf.udp-handoff-outcomes.v1").containsEntry("hasMore",true);
    @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)first.get("items");
    assertThat(items).extracting(i->i.get("sourceRevision")).containsExactly("1","2");
    var next=intake.outcomes("default","registry","run-1",((Number)first.get("nextAfter")).longValue(),2);
    assertThat(next).containsEntry("hasMore",false);
    assertThat((List<?>)next.get("items")).hasSize(1);
    assertThat(intake.outcomes("default","registry","run-1",0,2)).isEqualTo(first);
    assertThat((List<?>)intake.outcomes("default","survey","run-1",0,10).get("items")).isEmpty();
    assertThat((List<?>)intake.outcomes("default","registry","run-2",0,10).get("items")).isEmpty();
  }

  @Test void governedIdentityCoverageFollowsRetirementAndReactivation(){
    db.sql("truncate table ouf_udp.identity_lookup_token,ouf_udp.identity_lookup_shape,ouf_udp.identity_lookup_shape_catalog,ouf_udp.identity_lookup_coverage").update();
    var policy=new GovernedIdentityEngine.Policy("policy://identity/api","1","default","ouf:Road","registry",1,true,
        List.of(new GovernedIdentityEngine.Signal("ouf:name","ouf:name@semantic://publication/1",
            GovernedIdentityEngine.ComparatorKind.TEXT_V1,false,false,"assertion://name/1")),
        List.of(new GovernedIdentityEngine.SufficientRule("name",Set.of("ouf:name"),"assertion://rule/1")));
    assertThat(backfill.rebuild(policy).indexedObjects()).isZero();
    var mapping=new UdpPorts.MaterializationProfile("policy://authority/api",
        List.of(new UdpPorts.PropertyRule("name","ouf:name","string","OPEN",List.of("registry"))));
    when(config.resolveForSource(eq("bundle://road/9"),eq("ROAD"),anyString()))
        .thenReturn(new PublishedRuntimeConfiguration.Profiles(Map.of(),null,mapping,null,null,policy));
    loop=new PublishedResolutionLoop(jobs,config,gate,resolver,canonical,spatial,db,tx,relationships,governed,backfill);
    org.springframework.test.util.ReflectionTestUtils.setField(loop,"tenant","default");
    String indexed="select indexed_objects::text||':'||complete from ouf_udp.identity_lookup_coverage where policy_ref='policy://identity/api'";
    UUID id=apply(envelope("registry","EVENT",1,null,false,"v1","a",1,9),"APPLIED");
    assertThat(db.sql(indexed).query(String.class).single()).isEqualTo("1:true");
    apply(envelope("registry","EVENT",2,null,true,"v1","b",2,9),"RETIRED");
    assertThat(db.sql(indexed).query(String.class).single()).isEqualTo("0:true");
    assertThat(db.sql("select count(*) from ouf_udp.identity_lookup_shape where urban_object_id=:u").param("u",id).query(Integer.class).single()).isZero();
    assertThat(apply(envelope("registry","EVENT",3,null,false,"v1","c",3,9),"REACTIVATED")).isEqualTo(id);
    assertThat(db.sql(indexed).query(String.class).single()).isEqualTo("1:true");
    assertThat(serving.current(id,reader)).containsEntry("status","ACTIVE");
  }

  @Test void anotherSourceEpochIsQuarantinedWithoutEffects(){
    UUID id=event("registry",1,false,"APPLIED");
    var row=envelope("registry","EVENT",2,null,false,"v1","b",2,1);
    @SuppressWarnings("unchecked") var obs=(Map<String,Object>)row.get("apiObservation");
    obs.put("epoch","second");
    String handoff=(String)row.get("handoffId");
    intake.accept(row);loop.tick();
    assertThat(db.sql("select state||':'||safe_failure_code from ouf_udp.materialization_job where handoff_id=:h").param("h",handoff).query(String.class).single())
        .isEqualTo("QUARANTINED:UDP_SOURCE_EPOCH_CHANGED");
    assertThat(intake.handoff(handoff)).doesNotContainKey("outcome");
    assertThat(db.sql("select last_revision from ouf_udp.api_source_object").query(Long.class).single()).isOne();
    assertThat(serving.current(id,reader).get("properties")).isEqualTo(Map.of("name","registry-1","surface",1));
  }
}
