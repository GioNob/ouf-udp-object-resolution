package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
class IdentityGovernanceRuntimeTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_UDP_DB_URL"));r.add("spring.datasource.username",()->required("OUF_UDP_DB_USER"));r.add("spring.datasource.password",()->required("OUF_UDP_DB_PASSWORD"));}
  @Autowired IdentityGovernanceService governance;@Autowired HandoffIntakeService intake;@Autowired ObjectResolutionService resolution;@Autowired ResolutionRepository jobs;@Autowired GovernedIdentityReviewRepository governedReviews;@Autowired GovernedIdentityResolutionService governed;@Autowired CanonicalMaterializer materializer;@Autowired JdbcClient db;
  private final TrustedHumanContext human=new TrustedHumanContext("HUMAN_USER","operator-1","tenant-1",Set.of("urban.merge.plan","urban.object.merge","urban.split.plan","urban.object.split","resolution.match.approve"),"authz://decision/1","corr-1");

  @BeforeEach void clean(){db.sql("truncate table ouf_udp.merge_resolution_issue,ouf_udp.merge_property_contribution_link,ouf_udp.human_resolution_decision,ouf_udp.split_resolution_issue,ouf_udp.relationship_identity_history,ouf_udp.source_binding_history,ouf_udp.object_identity_history,ouf_udp.governance_audit,ouf_udp.governance_plan,ouf_udp.spatial_resolution_issue,ouf_udp.urban_geometry_current,ouf_udp.urban_geometry,ouf_udp.relationship_issue,ouf_udp.relationship_revision,ouf_udp.relationship_contribution,ouf_udp.urban_relationship,ouf_udp.property_conflict,ouf_udp.property_value,ouf_udp.materialization_observation,ouf_udp.property_contribution,ouf_udp.object_revision,ouf_udp.resolution_issue,ouf_udp.resolution_decision,ouf_udp.source_binding,ouf_udp.urban_object,ouf_udp.materialization_job,ouf_udp.handoff_event,ouf_udp.handoff_intake restart identity cascade").update();}

  @Test void mergeRequiresDryRunThenMovesBindingsRepointsEdgesAndPreservesHistory(){UUID survivor=object("S"),merged=object("M"),target=object("T");binding("hm","registry","ROAD","m-1",merged);UUID edge=UUID.randomUUID();db.sql("insert into ouf_udp.urban_relationship(relationship_id,source_object_id,relation_iri,target_object_id) values(:i,:s,'ouf:connectedTo',:t)").param("i",edge).param("s",merged).param("t",target).update();var plan=governance.planMerge(survivor,merged,human);assertThat(plan.impact()).containsEntry("bindingsToMove",1).containsEntry("relationshipsToRepoint",1);var executed=governance.executeMerge(plan.planId(),0,"same real-world entity","merge-key-1",human);assertThat(executed.state()).isEqualTo("EXECUTED");assertThat(governance.executeMerge(plan.planId(),0,"same real-world entity","merge-key-1",human)).isEqualTo(executed);Map<String,Object> identity=governance.resolveIdentity(merged);assertThat(identity.get("status")).isEqualTo("MERGED");assertThat(identity.get("redirect_to")).isEqualTo(survivor);assertThat(db.sql("select urban_object_id from ouf_udp.source_binding where source_object_id='m-1'").query(UUID.class).single()).isEqualTo(survivor);assertThat(db.sql("select source_object_id from ouf_udp.urban_relationship where relationship_id=:i").param("i",edge).query(UUID.class).single()).isEqualTo(survivor);assertThat(db.sql("select previous_source_object_id from ouf_udp.relationship_identity_history where relationship_id=:i").param("i",edge).query(UUID.class).single()).isEqualTo(merged);assertThat(db.sql("select count(*) from ouf_udp.source_binding_history").query(Long.class).single()).isOne();assertThat(db.sql("select count(*) from ouf_udp.governance_audit").query(Long.class).single()).isEqualTo(2);}

  @Test void splitAllocatesEveryBindingOrMakesUnresolvedExplicit(){UUID original=object("O"),a=object("A"),b=object("B");binding("h1","registry","ROAD","one",original);binding("h2","survey","ROAD","two",original);Map<String,String> allocations=Map.of("registry|ROAD|one",a.toString(),"survey|ROAD|two","UNRESOLVED");var plan=governance.planSplit(original,List.of(a,b),allocations,human);assertThat(plan.impact()).containsEntry("unresolvedBindings",1);governance.executeSplit(plan.planId(),0,"source aggregate was incorrect","split-key-1",human);assertThat(governance.resolveIdentity(original).get("status")).isEqualTo("SPLIT");assertThat(db.sql("select count(*) from ouf_udp.source_binding_history").query(Long.class).single()).isEqualTo(2);assertThat(db.sql("select issue_type from ouf_udp.split_resolution_issue").query(String.class).single()).isEqualTo("BINDING_UNRESOLVED");assertThat(db.sql("select state from ouf_udp.source_binding where source_object_id='two'").query(String.class).single()).isEqualTo("UNRESOLVED");}

  @Test void machineActorAndStaleVersionCannotExecuteGovernance(){UUID a=object("A"),b=object("B");TrustedHumanContext machine=new TrustedHumanContext("SERVICE_IDENTITY","worker","tenant-1",Set.of("urban.merge.plan"),"authz://machine","corr-2");assertThatThrownBy(()->governance.planMerge(a,b,machine)).isInstanceOf(SecurityException.class);var plan=governance.planMerge(a,b,human);assertThatThrownBy(()->governance.executeMerge(plan.planId(),1,"reason","merge-key-stale",human)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");assertThat(db.sql("select status from ouf_udp.urban_object where urban_object_id=:i").param("i",b).query(String.class).single()).isEqualTo("ACTIVE");}

  @Test void humanIssueDecisionAddsBindingWithoutRewritingAutomaticDecision(){UUID a=seedCandidate("A","same"),b=seedCandidate("B","same");Map<String,Object> payload=handoff("review-h","incoming","same");intake.accept(payload);var profile=new UdpPorts.ResolutionProfile("ATTRIBUTE_EXACT","1","policy://resolution/1","ouf:Road","identity","name");assertThat(resolution.resolve("review-h",payload,profile).outcome()).isEqualTo("REVIEW_REQUIRED");UUID issue=db.sql("select issue_id from ouf_udp.resolution_issue").query(UUID.class).single();governance.decideResolutionIssue(issue,0,"APPROVE","operator inspected evidence",a,human);assertThat(db.sql("select state from ouf_udp.resolution_issue where issue_id=:i").param("i",issue).query(String.class).single()).isEqualTo("RESOLVED");assertThat(db.sql("select urban_object_id from ouf_udp.source_binding where source_object_id='incoming'").query(UUID.class).single()).isEqualTo(a);assertThat(db.sql("select count(*) from ouf_udp.resolution_decision where handoff_id='review-h' and outcome='REVIEW_REQUIRED'").query(Long.class).single()).isOne();assertThat(db.sql("select action from ouf_udp.human_resolution_decision").query(String.class).single()).isEqualTo("APPROVE_MATCH");assertThatThrownBy(()->db.sql("delete from ouf_udp.human_resolution_decision").update()).hasStackTraceContaining("append-only");assertThat(List.of(a,b)).contains(a);}

  @Test void governedHumanApprovalRequiresAnExactCandidateStillActiveInTheOriginalScope(){
    UUID selected=object("candidate");
    intake.accept(handoff("governed-review","incoming","same"));
    var retrieved=new GovernedIdentityEngine.Candidates("policy://identity/1","1","default","ouf:Road",
        "postgres-snapshot://test",true,List.of(new GovernedIdentityEngine.Candidate(selected,"default","ouf:Road",Map.of())));
    var review=new GovernedIdentityEngine.Decision(GovernedIdentityEngine.Outcome.REVIEW_REQUIRED,null,
        "UNRESOLVED_IDENTITY","policy://identity/1","1",List.of(
            new GovernedIdentityEngine.Assessment(selected,List.of(
                new GovernedIdentityEngine.Evidence("ouf:name",GovernedIdentityEngine.EvidenceKind.MISSING,
                    "handoff://governed-review",null,"TEXT_V1","assertion://name/1")),Set.of(),false)));
    governedReviews.record("governed-review",review,retrieved);
    UUID issue=db.sql("select issue_id from ouf_udp.resolution_issue where handoff_id='governed-review'")
        .query(UUID.class).single();
    assertThatThrownBy(()->governance.decideResolutionIssue(issue,0,"APPROVE","incorrect candidate",UUID.randomUUID(),human))
        .hasMessage("UDP_TARGET_NOT_A_CANDIDATE");
    db.sql("update ouf_udp.urban_object set tenant_id='other' where urban_object_id=:id").param("id",selected).update();
    assertThatThrownBy(()->governance.decideResolutionIssue(issue,0,"APPROVE","stale scope",selected,human))
        .hasMessage("UDP_GOVERNED_TARGET_STALE");
    assertThat(db.sql("select state from ouf_udp.resolution_issue where issue_id=:id").param("id",issue)
        .query(String.class).single()).isEqualTo("OPEN");
    db.sql("update ouf_udp.urban_object set tenant_id='default' where urban_object_id=:id").param("id",selected).update();
    governance.decideResolutionIssue(issue,0,"APPROVE","checked current object",selected,human);
    assertThat(db.sql("select urban_object_id from ouf_udp.source_binding where source_object_id='incoming'")
        .query(UUID.class).single()).isEqualTo(selected);
    var policy=new GovernedIdentityEngine.Policy("policy://identity/1","1","default","ouf:Road","roads",2,false,
        List.of(new GovernedIdentityEngine.Signal("ouf:name","ouf:name@semantic://1",
            GovernedIdentityEngine.ComparatorKind.TEXT_V1,false,false,"assertion://name/1")),
        List.of(new GovernedIdentityEngine.SufficientRule("name",Set.of("ouf:name"),"assertion://rule/1")));
    var mapping=new UdpPorts.MaterializationProfile("authority://1",List.of(
        new UdpPorts.PropertyRule("name","ouf:name","string","OPEN",List.of())));
    var resumed=governed.resolve("governed-review",handoff("governed-review","incoming","same"),mapping,policy);
    assertThat(resumed.outcome()).isEqualTo("MATCH");
    assertThat(resumed.targetUrbanObjectId()).isEqualTo(selected);
    assertThat(resumed.duplicate()).isTrue();
    assertThat(db.sql("select count(*) from ouf_udp.resolution_decision where handoff_id='governed-review'")
        .query(Long.class).single()).isOne();
  }

  @Test void governedHumanApprovalRejectsChangedIdentityContributions(){
    UUID selected=object("candidate");
    intake.accept(handoff("governed-stale","incoming","same"));
    var retrieved=new GovernedIdentityEngine.Candidates("policy://identity/1","1","default","ouf:Road",
        "postgres-snapshot://test",true,List.of(new GovernedIdentityEngine.Candidate(selected,"default","ouf:Road",Map.of())));
    var review=new GovernedIdentityEngine.Decision(GovernedIdentityEngine.Outcome.REVIEW_REQUIRED,null,
        "UNRESOLVED_IDENTITY","policy://identity/1","1",List.of(
            new GovernedIdentityEngine.Assessment(selected,List.of(
                new GovernedIdentityEngine.Evidence("ouf:name",GovernedIdentityEngine.EvidenceKind.MISSING,
                    "handoff://governed-stale",null,"TEXT_V1","assertion://name/1")),Set.of(),false)));
    governedReviews.record("governed-stale",review,retrieved);
    Map<String,Object> later=handoff("later-revision","candidate","New name");
    intake.accept(later);
    materializer.materialize("later-revision",selected,later,new UdpPorts.MaterializationProfile("authority://1",
        List.of(new UdpPorts.PropertyRule("name","ouf:name","string","OPEN",List.of("roads")))));
    UUID issue=db.sql("select issue_id from ouf_udp.resolution_issue where handoff_id='governed-stale'")
        .query(UUID.class).single();
    assertThatThrownBy(()->governance.decideResolutionIssue(issue,0,"APPROVE","stale evidence",selected,human))
        .hasMessage("UDP_GOVERNED_EVIDENCE_STALE");
  }

  @Test void existingActiveSourceBindingPreservesIdentityWhenItsPropertiesChange(){
    UUID existing=object("bound-road");
    binding("original-binding","roads","ROAD","stable-source-record",existing);
    Map<String,Object> changed=handoff("bound-change","stable-source-record","Renamed road");
    intake.accept(changed);
    var policy=new GovernedIdentityEngine.Policy("policy://identity/binding","1","default","ouf:Road",
        "roads",2,true,List.of(new GovernedIdentityEngine.Signal("ouf:name",
            "ouf:name@semantic://1",GovernedIdentityEngine.ComparatorKind.TEXT_V1,false,false,
            "assertion://name/1")),List.of(new GovernedIdentityEngine.SufficientRule("name",
            Set.of("ouf:name"),"assertion://name-sufficient/1")));
    var mapping=new UdpPorts.MaterializationProfile("authority://1",List.of(
        new UdpPorts.PropertyRule("name","ouf:name","string","OPEN",List.of())));
    var result=governed.resolve("bound-change",changed,mapping,policy);
    assertThat(result.outcome()).isEqualTo("MATCH");
    assertThat(result.targetUrbanObjectId()).isEqualTo(existing);
    assertThat(db.sql("select count(*) from ouf_udp.resolution_issue where handoff_id='bound-change'")
        .query(Long.class).single()).isZero();
    assertThat(db.sql("select candidate_ref from ouf_udp.resolution_decision where handoff_id='bound-change'")
        .query(String.class).single()).startsWith("source-binding://");
  }

  @Test void ambiguousHandoffWaitsForHumanThenResumesWithoutChangingDurableAck(){
    UUID selected=seedCandidate("A","same");seedCandidate("B","same");
    Map<String,Object> payload=handoff("review-resume","incoming","same");
    intake.accept(payload);
    var profile=new UdpPorts.ResolutionProfile("ATTRIBUTE_EXACT","1","policy://resolution/1","ouf:Road","identity","name");
    var claim=jobs.claim("review-worker",java.time.Duration.ofMinutes(2)).orElseThrow();
    assertThat(resolution.resolve("review-resume",payload,profile).outcome()).isEqualTo("REVIEW_REQUIRED");
    jobs.quarantine(claim,"UDP_RESOLUTION_REVIEW_REQUIRED");
    assertThat(db.sql("select state from ouf_udp.materialization_job where handoff_id='review-resume'").query(String.class).single()).isEqualTo("QUARANTINED");
    assertThat(db.sql("select state from ouf_udp.handoff_intake where handoff_id='review-resume'").query(String.class).single()).isEqualTo("DURABLE");
    assertThat(jobs.claim("other-worker",java.time.Duration.ofMinutes(2))).isEmpty();
    UUID issue=db.sql("select issue_id from ouf_udp.resolution_issue where handoff_id='review-resume'").query(UUID.class).single();
    governance.decideResolutionIssue(issue,0,"APPROVE","verified same object",selected,human);
    assertThat(db.sql("select state from ouf_udp.materialization_job where handoff_id='review-resume'").query(String.class).single()).isEqualTo("READY");
    var resumed=jobs.claim("review-worker",java.time.Duration.ofMinutes(2)).orElseThrow();
    var decision=resolution.resolve(resumed.handoffId(),resumed.payload(),profile);
    assertThat(decision.outcome()).isEqualTo("MATCH");
    assertThat(decision.targetUrbanObjectId()).isEqualTo(selected);
    assertThat(db.sql("select count(*) from ouf_udp.resolution_decision where handoff_id='review-resume'").query(Long.class).single()).isOne();
  }

  @Test void actorHeadersAreRejectedInsteadOfBecomingTrustContext(){MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader("X-Actor-Type","HUMAN_USER");request.setAttribute("ouf.actorType","HUMAN_USER");request.setAttribute("ouf.subject","operator");request.setAttribute("ouf.tenantId","tenant");request.setAttribute("ouf.capabilities",Set.of("urban.object.merge"));request.setAttribute("ouf.authorizationDecisionRef","authz://1");request.setAttribute("ouf.correlationId","corr");assertThatThrownBy(()->TrustedHumanApi.trusted(request)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");}

  private UUID object(String key){UUID id=UUID.randomUUID();db.sql("insert into ouf_udp.urban_object(urban_object_id,canonical_type,canonical_key,match_key) values(:i,'ouf:Road',:k,:m)").param("i",id).param("k",key.toLowerCase()).param("m",key.toLowerCase()).update();return id;}
  private UUID seedCandidate(String key,String match){UUID id=UUID.randomUUID();db.sql("insert into ouf_udp.urban_object(urban_object_id,canonical_type,canonical_key,match_key) values(:i,'ouf:Road',:k,:m)").param("i",id).param("k",key.toLowerCase()).param("m",match).update();return id;}
  private void binding(String handoff,String source,String type,String sourceObject,UUID target){db.sql("insert into ouf_udp.handoff_intake(handoff_id,ingestion_run_id,ingestion_id,source_id,type_code,source_object_id,content_hash,payload_json,receipt_ref) values(:h,'run','ing',:s,:t,:o,:c,'{}',:r)").param("h",handoff).param("s",source).param("t",type).param("o",sourceObject).param("c","sha256:"+handoff).param("r","udp://"+handoff).update();db.sql("insert into ouf_udp.source_binding(source_id,type_code,source_object_id,urban_object_id,first_handoff_id) values(:s,:t,:o,:u,:h)").param("s",source).param("t",type).param("o",sourceObject).param("u",target).param("h",handoff).update();}
  private static Map<String,Object> handoff(String id,String object,String name){return new LinkedHashMap<>(Map.ofEntries(Map.entry("handoffId",id),Map.entry("ingestionRunId","run-1"),Map.entry("ingestionId","ing-"+id),Map.entry("sourceIdentity",new LinkedHashMap<>(Map.of("sourceId","roads","typeCode","ROAD","sourceObjectId",object))),Map.entry("operation","UPSERT"),Map.entry("canonicalPayload",Map.of("identity",object,"name",name)),Map.entry("contractRefs",Map.of("sourceSchemaRef","schema://1","bundleRef","bundle://1","semanticPublicationSetRef","semantic://1","adapterProfileRef","adapter://1")),Map.entry("lineageId","lineage-"+id),Map.entry("contentHash","sha256:"+id),Map.entry("acquiredAt","2026-09-13T00:00:00Z"),Map.entry("changeRepresentation",Map.of("mode","FULL_SNAPSHOT"))));}
  private static String required(String n){String v=System.getenv(n);if(v==null)throw new IllegalStateException(n+" required");return v;}
}
