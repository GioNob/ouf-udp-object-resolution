package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class ScopedIdentityCandidateRepositoryRuntimeTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",()->required("OUF_UDP_DB_URL"));
    r.add("spring.datasource.username",()->required("OUF_UDP_DB_USER"));
    r.add("spring.datasource.password",()->required("OUF_UDP_DB_PASSWORD"));
  }
  @Autowired ScopedIdentityCandidateRepository candidates;
  @Autowired HandoffIntakeService intake;
  @Autowired ObjectResolutionService resolver;
  @Autowired CanonicalMaterializer materializer;
  @Autowired GovernedIdentityIndexBackfill backfill;
  @Autowired GovernedIdentityPreflight preflight;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
  @Autowired GovernedIdentityResolutionService governed;
  @Autowired IdentityGovernanceService governance;
  @Autowired ResolutionRepository jobs;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @Autowired JdbcClient db;
  @Autowired org.springframework.transaction.support.TransactionTemplate tx;
  @org.junit.jupiter.api.BeforeEach void clean(){
    db.sql("truncate table ouf_udp.identity_lookup_token,ouf_udp.identity_lookup_shape,ouf_udp.identity_lookup_shape_catalog,ouf_udp.identity_lookup_coverage").update();
  }

  @Test void neverInfersCompletenessFromClassSizeOrEmptyResults(){
    var policy=policy();
    var subject=subject("Alpha");
    var result=candidates.retrieve(policy,subject);
    assertThat(result.complete()).isFalse();
    assertThat(result.rows()).isEmpty();
    assertThat(result.coverageRef()).isNull();
    assertThat(new GovernedIdentityEngine().decide(policy,subject,result).outcome())
        .isEqualTo(GovernedIdentityEngine.Outcome.REVIEW_REQUIRED);
    assertThat(new GovernedIdentityEngine().decide(policy,subject,result).reason())
        .isEqualTo("CANDIDATE_COVERAGE_UNVERIFIED");
  }
  @Test void incrementalRefreshRecertifiesOnlyOneMaterializedObjectInTheSameTransaction(){
    clearObjects();
    materialize("existing","Alpha");
    var policy=policy();
    assertThat(backfill.rebuild(policy).indexedObjects()).isEqualTo(1);
    tx.executeWithoutResult(status->{
      var before=candidates.retrieve(policy,subject("Beta"));
      assertThat(before.complete()).isTrue();
      UUID added=materialize("added","Beta");
      assertThat(candidates.retrieve(policy,subject("Beta")).complete()).isFalse();
      backfill.refreshOne(policy,added,before.coverageRef());
      assertThat(candidates.retrieve(policy,subject("Beta")).rows())
          .extracting(GovernedIdentityEngine.Candidate::objectId).containsExactly(added);
    });
    assertThat(db.sql("select indexed_objects from ouf_udp.identity_lookup_coverage where policy_ref=:p")
        .param("p",policy.ref()).query(Long.class).single()).isEqualTo(2);
    assertThat(candidates.retrieve(policy,subject("Alpha")).complete()).isTrue();
  }
  @Test void publishedWorkerUsesGovernedDecisionAndKeepsCoverageComplete(){
    clearObjects();
    var policy=policy();
    assertThat(backfill.rebuild(policy).indexedObjects()).isZero();
    var configuration=org.mockito.Mockito.mock(PublishedRuntimeConfiguration.class);
    var gate=org.mockito.Mockito.mock(MaterializationReferenceGate.class);
    org.mockito.Mockito.when(gate.verify(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    var mapping=new UdpPorts.MaterializationProfile("policy://authority/1",
        List.of(new UdpPorts.PropertyRule("name","ouf:name","string","OPEN",List.of("registry"))));
    org.mockito.Mockito.when(configuration.resolve("bundle://road/1","ROAD"))
        .thenReturn(new PublishedRuntimeConfiguration.Profiles(Map.of(),null,mapping,null,null,policy));
    var loop=new PublishedResolutionLoop(jobs,configuration,gate,resolver,materializer,null,db,
        new org.springframework.transaction.support.TransactionTemplate(transactions),null,governed,backfill);
    intake.accept(envelope("worker-governed","Alpha"));
    loop.tick();
    assertThat(db.sql("select state from ouf_udp.materialization_job where handoff_id='candidate-worker-governed'")
        .query(String.class).single()).isEqualTo("SUCCEEDED");
    assertThat(candidates.retrieve(policy,subject("Alpha")).complete()).isTrue();
    assertThat(db.sql("select indexed_objects from ouf_udp.identity_lookup_coverage where policy_ref=:p")
        .param("p",policy.ref()).query(Long.class).single()).isOne();
  }
  @Test void humanApprovedReviewResumesAndMaterializesTheObservation(){
    clearObjects();
    UUID existing=materializeWithAddress("review-target","Alpha","Via Roma");
    db.sql("update ouf_udp.materialization_job set state='SUCCEEDED' where handoff_id='candidate-review-target'")
        .update();
    var policy=new GovernedIdentityEngine.Policy("policy://identity/review-resume","1","default",
        "ouf:Road","registry",5,true,List.of(
        new GovernedIdentityEngine.Signal("ouf:name","ouf:name@semantic://publication/1",
            GovernedIdentityEngine.ComparatorKind.TEXT_V1,false,false,"assertion://name/1"),
        new GovernedIdentityEngine.Signal("ouf:address","ouf:address@semantic://publication/1",
            GovernedIdentityEngine.ComparatorKind.TEXT_V1,false,false,"assertion://address/1")),
        List.of(new GovernedIdentityEngine.SufficientRule("both",Set.of("ouf:name","ouf:address"),
            "assertion://both/1")));
    backfill.rebuild(policy);
    var mapping=new UdpPorts.MaterializationProfile("policy://authority/1",List.of(
        new UdpPorts.PropertyRule("name","ouf:name","string","OPEN",List.of("registry")),
        new UdpPorts.PropertyRule("address","ouf:address","string","OPEN",List.of("registry"))));
    Map<String,Object> incoming=envelope("reviewed","Alpha");
    intake.accept(incoming);
    assertThat(governed.resolve("candidate-reviewed",incoming,mapping,policy).outcome())
        .isEqualTo("REVIEW_REQUIRED");
    var configuration=org.mockito.Mockito.mock(PublishedRuntimeConfiguration.class);
    var gate=org.mockito.Mockito.mock(MaterializationReferenceGate.class);
    org.mockito.Mockito.when(gate.verify(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    org.mockito.Mockito.when(configuration.resolve("bundle://road/1","ROAD"))
        .thenReturn(new PublishedRuntimeConfiguration.Profiles(Map.of(),null,mapping,null,null,policy));
    var loop=new PublishedResolutionLoop(jobs,configuration,gate,resolver,materializer,null,db,
        new org.springframework.transaction.support.TransactionTemplate(transactions),null,governed,backfill);
    loop.tick();
    assertThat(db.sql("select state from ouf_udp.materialization_job where handoff_id='candidate-reviewed'")
        .query(String.class).single()).isEqualTo("QUARANTINED");
    UUID issue=db.sql("select issue_id from ouf_udp.resolution_issue where handoff_id='candidate-reviewed'")
        .query(UUID.class).single();
    var human=new TrustedHumanContext("HUMAN_USER","reviewer","default",
        Set.of("resolution.match.approve"),"authz://review","corr-review");
    governance.decideResolutionIssue(issue,0,"APPROVE","checked canonical object",existing,human);
    loop.tick();
    assertThat(db.sql("select state from ouf_udp.materialization_job where handoff_id='candidate-reviewed'")
        .query(String.class).single()).isEqualTo("SUCCEEDED");
    assertThat(db.sql("select count(*) from ouf_udp.materialization_observation where handoff_id='candidate-reviewed'")
        .query(Long.class).single()).isOne();
    assertThat(candidates.retrieve(policy,new GovernedIdentityEngine.Subject("default","ouf:Road",
        "registry",Map.of("ouf:name",new GovernedIdentityEngine.Value(policy.signals().getFirst().semanticRef(),
            "Alpha","handoff://reviewed")))).complete()).isTrue();
  }
  @Test void humanCanConfirmDistinctIdentityAndWorkerMaterializesItAtomically(){
    clearObjects();
    UUID existing=materializeWithAddress("distinct-target","Alpha","Via Roma");
    db.sql("update ouf_udp.materialization_job set state='SUCCEEDED' where handoff_id='candidate-distinct-target'").update();
    var policy=new GovernedIdentityEngine.Policy("policy://identity/distinct-review","1","default",
        "ouf:Road","registry",5,true,List.of(
        new GovernedIdentityEngine.Signal("ouf:name","ouf:name@semantic://publication/1",
            GovernedIdentityEngine.ComparatorKind.TEXT_V1,false,false,"assertion://name/1"),
        new GovernedIdentityEngine.Signal("ouf:address","ouf:address@semantic://publication/1",
            GovernedIdentityEngine.ComparatorKind.TEXT_V1,false,false,"assertion://address/1")),
        List.of(new GovernedIdentityEngine.SufficientRule("both",Set.of("ouf:name","ouf:address"),
            "assertion://both/1")));
    backfill.rebuild(policy);
    var mapping=new UdpPorts.MaterializationProfile("policy://authority/1",List.of(
        new UdpPorts.PropertyRule("name","ouf:name","string","OPEN",List.of("registry")),
        new UdpPorts.PropertyRule("address","ouf:address","string","OPEN",List.of("registry"))));
    Map<String,Object> incoming=envelope("distinct-reviewed","Alpha");
    intake.accept(incoming);
    assertThat(governed.resolve("candidate-distinct-reviewed",incoming,mapping,policy).outcome())
        .isEqualTo("REVIEW_REQUIRED");
    var configuration=org.mockito.Mockito.mock(PublishedRuntimeConfiguration.class);
    var gate=org.mockito.Mockito.mock(MaterializationReferenceGate.class);
    org.mockito.Mockito.when(gate.verify(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    org.mockito.Mockito.when(configuration.resolve("bundle://road/1","ROAD"))
        .thenReturn(new PublishedRuntimeConfiguration.Profiles(Map.of(),null,mapping,null,null,policy));
    var loop=new PublishedResolutionLoop(jobs,configuration,gate,resolver,materializer,null,db,
        new org.springframework.transaction.support.TransactionTemplate(transactions),null,governed,backfill);
    loop.tick();
    assertThat(db.sql("select state from ouf_udp.materialization_job where handoff_id='candidate-distinct-reviewed'")
        .query(String.class).single()).isEqualTo("QUARANTINED");
    UUID issue=db.sql("select issue_id from ouf_udp.resolution_issue where handoff_id='candidate-distinct-reviewed'")
        .query(UUID.class).single();
    var human=new TrustedHumanContext("HUMAN_USER","reviewer","default",
        Set.of("resolution.match.approve"),"authz://review","corr-distinct");
    governance.decideResolutionIssue(issue,0,"CREATE_NEW","verified distinct physical object",null,human);
    assertThat(db.sql("select count(*) from ouf_udp.urban_object").query(Long.class).single()).isOne();
    backfill.rebuild(policy);
    loop.tick();
    assertThat(db.sql("select state from ouf_udp.resolution_issue where issue_id=:i").param("i",issue)
        .query(String.class).single()).isEqualTo("OPEN");
    assertThat(db.sql("select version from ouf_udp.resolution_issue where issue_id=:i").param("i",issue)
        .query(Long.class).single()).isEqualTo(2L);
    assertThat(db.sql("select count(*) from ouf_udp.urban_object").query(Long.class).single()).isOne();
    governance.decideResolutionIssue(issue,2,"CREATE_NEW","rechecked distinct physical object",null,human);
    loop.tick();
    assertThat(db.sql("select state from ouf_udp.materialization_job where handoff_id='candidate-distinct-reviewed'")
        .query(String.class).single()).isEqualTo("SUCCEEDED");
    UUID created=db.sql("select urban_object_id from ouf_udp.source_binding where source_object_id='distinct-reviewed'")
        .query(UUID.class).single();
    assertThat(created).isNotEqualTo(existing);
    assertThat(db.sql("select count(*) from ouf_udp.materialization_observation where handoff_id='candidate-distinct-reviewed'")
        .query(Long.class).single()).isOne();
    assertThat(db.sql("select count(*) from ouf_udp.human_resolution_decision where issue_id=:i and action='CREATE_NEW'")
        .param("i",issue).query(Long.class).single()).isEqualTo(2L);
    assertThat(db.sql("select complete from ouf_udp.identity_lookup_coverage where policy_ref=:p")
        .param("p",policy.ref()).query(Boolean.class).single()).isTrue();
  }

  @Test void structuredCanonicalValuesAreIndexedAndComparedAcrossJsonKeyOrder(){
    clearObjects();
    Map<String,Object> envelope=envelope("json","ignored");
    envelope.put("canonicalPayload",Map.of("code","json","details",
        Map.of("b",List.of(2,Map.of("z",true,"a",1)),"a","value")));
    intake.accept(envelope);
    UUID object=resolver.resolve("candidate-json",envelope,new UdpPorts.ResolutionProfile(
        "CANONICAL_KEY","1","policy://resolution/1","ouf:Road","code","code")).targetUrbanObjectId();
    materializer.materialize("candidate-json",object,envelope,new UdpPorts.MaterializationProfile(
        "policy://authority/1",List.of(new UdpPorts.PropertyRule("details","ouf:details","json","OPEN",List.of("registry")))));
    var signal=new GovernedIdentityEngine.Signal("ouf:details","ouf:details@semantic://publication/1",
        GovernedIdentityEngine.ComparatorKind.JSON_V1,false,false,"assertion://details/1");
    var policy=new GovernedIdentityEngine.Policy("policy://identity/json","1","default","ouf:Road",
        "registry",2,true,List.of(signal),List.of(new GovernedIdentityEngine.SufficientRule(
            "all",Set.of("ouf:details"),"assertion://all/1")));
    assertThat(backfill.rebuild(policy).indexedObjects()).isOne();
    var incoming=new GovernedIdentityEngine.Subject("default","ouf:Road","registry",Map.of(
        "ouf:details",new GovernedIdentityEngine.Value(signal.semanticRef(),
            "{\"a\":\"value\",\"b\":[2.0,{\"a\":1.0,\"z\":true}]}","handoff://json")));
    var retrieved=candidates.retrieve(policy,incoming);
    assertThat(retrieved.complete()).isTrue();
    assertThat(retrieved.rows()).extracting(GovernedIdentityEngine.Candidate::objectId).containsExactly(object);
    assertThat(new GovernedIdentityEngine().decide(policy,incoming,retrieved).outcome())
        .isEqualTo(GovernedIdentityEngine.Outcome.MATCH);
  }
  @Test void frozenConfigurationPreflightAttestsOnlyCurrentCompleteCoverage()throws Exception{
    clearObjects();
    materialize("preflight","Alpha");
    var policy=policy();
    var signal=policy.signals().getFirst();
    Map<String,Object> rawPolicy=Map.of("ref",policy.ref(),"version",policy.version(),
        "tenantId",policy.tenantId(),"canonicalClass",policy.canonicalClass(),
        "sourceId",policy.sourceId(),"maxCandidates",policy.maxCandidates(),
        "allowAutoNew",policy.allowAutoNew(),"signals",List.of(Map.of("id",signal.id(),
            "semanticRef",signal.semanticRef(),"comparator",signal.comparator().name(),
            "excludesOnDisagreement",false,"uniqueWithinScope",false,
            "assertionRef",signal.assertionRef())),"sufficientRules",List.of(Map.of(
                "id","name","signalIds",List.of("ouf:name"),"assertionRef","assertion://rule/1")));
    Map<String,Object> configuration=Map.of("semanticMapping",Map.of(
            "sourceType",Map.of("sourceId","registry","typeCode","ROAD"),
            "targetClasses",List.of(Map.of("classIri","ouf:Road")),
            "propertyMappings",List.of(Map.of("targetPropertyIri","ouf:name"))),
        "extractionProfile",Map.of("runtime",Map.of("udp",Map.of(
            "resolution",Map.of("strategyId","GOVERNED_IDENTITY","strategyVersion","1",
                "policyRef",policy.ref(),"governedIdentity",rawPolicy),
            "materialization",Map.of("policyRef","policy://authority/1","checkpointInterval",1,
                "bitemporalProperties",List.of(),"properties",List.of(Map.of(
                "sourceField","ouf:name","propertyIri","ouf:name","datatype","string",
                "accessLabel","OPEN","authorityOrder",List.of("registry"))))))));
    String hash="sha256:"+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(json.copy().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true)
            .writeValueAsBytes(configuration)));
    var admin=new TrustedHumanContext("HUMAN_USER","ouf-admin","default",
        Set.of("urban.identity.preflight"),"authz://preflight","corr-preflight");
    assertThatThrownBy(()->preflight.prepare("registry","sha256:wrong",configuration,admin))
        .hasMessage("UDP_IDENTITY_PREFLIGHT_CONFIGURATION_HASH");
    var attestation=preflight.prepare("registry",hash,configuration,admin);
    assertThat(attestation.valid()).isTrue();
    assertThat(attestation.indexedObjects()).isOne();
    db.sql("update ouf_udp.urban_object_current_state set updated_at=transaction_timestamp()")
        .update();
    assertThat(preflight.status(attestation.attestationId(),admin).valid()).isFalse();
  }
  @Test void indexedSeedFindsOnlyMatchingCurrentObjectAndMutationInvalidatesCoverage(){
    clearObjects();
    UUID matching=materialize("one","Alpha");materialize("two","Beta");
    assertThat(backfill.rebuild(policy()).indexedObjects()).isEqualTo(2);
    db.sql("update ouf_udp.identity_lookup_coverage set field_set_hash=:shape where policy_ref='policy://identity/1'")
        .param("shape",ScopedIdentityCandidateRepository.hash("ouf:another-field")).update();
    assertThat(candidates.retrieve(policy(),subject("ALPHA")).complete()).isFalse();
    db.sql("update ouf_udp.identity_lookup_coverage set field_set_hash=:shape where policy_ref='policy://identity/1'")
        .param("shape",ScopedIdentityCandidateRepository.hash("ouf:name")).update();
    var changedComparator=new GovernedIdentityEngine.Policy(policy().ref(),policy().version(),
        policy().tenantId(),policy().canonicalClass(),policy().sourceId(),1,true,
        List.of(new GovernedIdentityEngine.Signal("ouf:name","ouf:name@semantic://publication/1",
            GovernedIdentityEngine.ComparatorKind.CONCEPT,false,false,"assertion://name/1")),
        policy().sufficientRules());
    assertThat(candidates.retrieve(changedComparator,subject("ALPHA")).complete()).isFalse();
    var changedRule=new GovernedIdentityEngine.Policy(policy().ref(),policy().version(),
        policy().tenantId(),policy().canonicalClass(),policy().sourceId(),1,true,
        policy().signals(),List.of(new GovernedIdentityEngine.SufficientRule("name",
            Set.of("ouf:name"),"assertion://different-rule/2")));
    assertThat(candidates.retrieve(changedRule,subject("ALPHA")).complete()).isFalse();
    var result=candidates.retrieve(policy(),subject("ALPHA"));
    assertThat(result.complete()).isTrue();
    assertThat(result.rows()).extracting(GovernedIdentityEngine.Candidate::objectId).containsExactly(matching);
    assertThat(new GovernedIdentityEngine().decide(policy(),subject("ALPHA"),result).outcome())
        .isEqualTo(GovernedIdentityEngine.Outcome.MATCH);
    db.sql("update ouf_udp.urban_object_current_state set updated_at=transaction_timestamp() where urban_object_id=:id")
        .param("id",matching).update();
    assertThat(candidates.retrieve(policy(),subject("ALPHA")).complete()).isFalse();
    assertThat(backfill.rebuild(policy()).indexedObjects()).isEqualTo(2);
    db.sql("insert into ouf_udp.urban_object(urban_object_id,tenant_id,canonical_type) values(gen_random_uuid(),'default','ouf:Road')").update();
    assertThat(candidates.retrieve(policy(),subject("ALPHA")).complete()).isFalse();
    assertThatThrownBy(()->backfill.rebuild(policy())).hasMessageContaining("UNMATERIALIZED_OBJECT");
    assertThat(candidates.retrieve(policy(),subject("ALPHA")).complete()).isFalse();
  }
  @Test void backfillRejectsDivergentCanonicalProjection(){
    clearObjects();
    UUID id=materialize("three","Alpha");
    db.sql("update ouf_udp.urban_object_current_state set canonical_payload=cast(:payload as jsonb) where urban_object_id=:id")
        .param("id",id).param("payload","{\"ouf:name\":\"Other\"}").update();
    assertThatThrownBy(()->backfill.rebuild(policy())).hasMessageContaining("CURRENT_VALUE_MISMATCH");
    assertThat(candidates.retrieve(policy(),subject("Alpha")).complete()).isFalse();
  }
  @Test void unionsIndexedSeedsFromEveryExposedField(){
    clearObjects();
    UUID first=materializeWithAddress("a","Alpha","Via Roma");
    UUID second=materializeWithAddress("b","Beta","Via Milano");
    var policy=new GovernedIdentityEngine.Policy("policy://identity/two","1","default","ouf:Road","registry",2,true,
        List.of(new GovernedIdentityEngine.Signal("ouf:name","ouf:name@semantic://publication/1",
                GovernedIdentityEngine.ComparatorKind.TEXT_V1,false,false,"assertion://name/1"),
            new GovernedIdentityEngine.Signal("ouf:address","ouf:address@semantic://publication/1",
                GovernedIdentityEngine.ComparatorKind.TEXT_V1,false,false,"assertion://address/1")),
        List.of(new GovernedIdentityEngine.SufficientRule("whole",Set.of("ouf:name","ouf:address"),"assertion://whole/1")));
    assertThat(backfill.rebuild(policy).indexedObjects()).isEqualTo(2);
    var subject=new GovernedIdentityEngine.Subject("default","ouf:Road","registry",Map.of(
        "ouf:name",new GovernedIdentityEngine.Value("ouf:name@semantic://publication/1","Beta","handoff://probe/name"),
        "ouf:address",new GovernedIdentityEngine.Value("ouf:address@semantic://publication/1","Via Roma","handoff://probe/address")));
    var result=candidates.retrieve(policy,subject);
    assertThat(result.complete()).isTrue();
    assertThat(result.rows()).extracting(GovernedIdentityEngine.Candidate::objectId).containsExactlyInAnyOrder(first,second);
    assertThat(new GovernedIdentityEngine().decide(policy,subject,result).outcome())
        .isEqualTo(GovernedIdentityEngine.Outcome.REVIEW_REQUIRED);
    var narrow=new GovernedIdentityEngine.Policy(policy.ref(),policy.version(),policy.tenantId(),
        policy.canonicalClass(),policy.sourceId(),1,true,policy.signals(),policy.sufficientRules());
    assertThat(new GovernedIdentityEngine().decide(narrow,subject,candidates.retrieve(narrow,subject)).outcome())
        .isEqualTo(GovernedIdentityEngine.Outcome.RESOLUTION_TOO_BROAD);
  }
  @Test void verifiedEmptyScopeCanCreateFirstObjectWithSubsetOfConfiguredFields(){
    clearObjects();
    var base=policy();
    var two=new GovernedIdentityEngine.Policy("policy://identity/empty","1","default","ouf:Road","registry",2,true,
        List.of(base.signals().getFirst(),new GovernedIdentityEngine.Signal("ouf:address",
            "ouf:address@semantic://publication/1",GovernedIdentityEngine.ComparatorKind.TEXT_V1,
            false,false,"assertion://address/1")),
        List.of(new GovernedIdentityEngine.SufficientRule("whole",Set.of("ouf:name","ouf:address"),"assertion://whole/1")));
    assertThat(backfill.rebuild(two).indexedObjects()).isZero();
    var result=candidates.retrieve(two,subject("Alpha"));
    assertThat(result.complete()).isTrue();
    assertThat(result.rows()).isEmpty();
    assertThat(new GovernedIdentityEngine().decide(two,subject("Alpha"),result).outcome())
        .isEqualTo(GovernedIdentityEngine.Outcome.NEW_OBJECT);
    db.sql("insert into ouf_udp.urban_object(urban_object_id,tenant_id,canonical_type) values(gen_random_uuid(),'default','ouf:Road')").update();
    assertThat(candidates.retrieve(two,subject("Alpha")).complete()).isFalse();
  }
  @Test void differentFieldShapesAreIndexedAsPotentiallyUncertainCandidates(){
    clearObjects();
    UUID smaller=materialize("small","Alpha");
    materializeWithAddress("large","Beta","Via Milano");
    var base=policy();
    var two=new GovernedIdentityEngine.Policy("policy://identity/heterogeneous","1","default","ouf:Road","registry",2,true,
        List.of(base.signals().getFirst(),new GovernedIdentityEngine.Signal("ouf:address",
            "ouf:address@semantic://publication/1",GovernedIdentityEngine.ComparatorKind.TEXT_V1,
            false,false,"assertion://address/1")),
        List.of(new GovernedIdentityEngine.SufficientRule("whole",Set.of("ouf:name","ouf:address"),"assertion://whole/1")));
    assertThat(backfill.rebuild(two).indexedObjects()).isEqualTo(2);
    var incoming=new GovernedIdentityEngine.Subject("default","ouf:Road","registry",Map.of(
        "ouf:name",new GovernedIdentityEngine.Value("ouf:name@semantic://publication/1","Alpha","handoff://probe/name"),
        "ouf:address",new GovernedIdentityEngine.Value("ouf:address@semantic://publication/1","Via Roma","handoff://probe/address")));
    var retrieved=candidates.retrieve(two,incoming);
    assertThat(retrieved.complete()).isTrue();
    assertThat(retrieved.rows()).extracting(GovernedIdentityEngine.Candidate::objectId).containsExactly(smaller);
    assertThat(new GovernedIdentityEngine().decide(two,incoming,retrieved).outcome())
        .isEqualTo(GovernedIdentityEngine.Outcome.REVIEW_REQUIRED);
    var allDifferent=new GovernedIdentityEngine.Subject("default","ouf:Road","registry",Map.of(
        "ouf:name",new GovernedIdentityEngine.Value("ouf:name@semantic://publication/1","Other","handoff://probe/name"),
        "ouf:address",new GovernedIdentityEngine.Value("ouf:address@semantic://publication/1","Via Nuova","handoff://probe/address")));
    assertThat(new GovernedIdentityEngine().decide(two,allDifferent,candidates.retrieve(two,allDifferent)).outcome())
        .isEqualTo(GovernedIdentityEngine.Outcome.NEW_OBJECT);
  }
  @Test void aDisjointFieldSetRemainsUncertainWithoutScanningEveryDifferentShape(){
    clearObjects();
    UUID nameOnly=materialize("name-only","Alpha");
    materialize("different-name","Beta");
    var base=policy();
    var two=new GovernedIdentityEngine.Policy("policy://identity/disjoint","1","default","ouf:Road",
        "registry",1,true,List.of(base.signals().getFirst(),new GovernedIdentityEngine.Signal(
            "ouf:address","ouf:address@semantic://publication/1",GovernedIdentityEngine.ComparatorKind.TEXT_V1,
            false,false,"assertion://address/1")),List.of(new GovernedIdentityEngine.SufficientRule(
            "address",Set.of("ouf:address"),"assertion://address-sufficient/1")));
    assertThat(backfill.rebuild(two).indexedObjects()).isEqualTo(2);
    var incoming=new GovernedIdentityEngine.Subject("default","ouf:Road","registry",Map.of(
        "ouf:address",new GovernedIdentityEngine.Value("ouf:address@semantic://publication/1",
            "Via Roma","handoff://incoming/address")));
    var retrieved=candidates.retrieve(two,incoming);
    assertThat(retrieved.rows()).hasSize(2);
    assertThat(new GovernedIdentityEngine().decide(two,incoming,retrieved).outcome())
        .isEqualTo(GovernedIdentityEngine.Outcome.RESOLUTION_TOO_BROAD);
    assertThat(retrieved.rows()).extracting(GovernedIdentityEngine.Candidate::objectId).contains(nameOnly);
  }
  @Test void differingSharedFieldsDoNotMakeHeterogeneousLookupTooBroad(){
    clearObjects();
    for(int i=0;i<5;i++)materialize("road-"+i,"Other-"+i);
    var base=policy();
    var two=new GovernedIdentityEngine.Policy("policy://identity/distinct-shapes","1","default","ouf:Road",
        "registry",1,true,List.of(base.signals().getFirst(),new GovernedIdentityEngine.Signal(
            "ouf:address","ouf:address@semantic://publication/1",GovernedIdentityEngine.ComparatorKind.TEXT_V1,
            false,false,"assertion://address/1")),List.of(new GovernedIdentityEngine.SufficientRule(
            "whole",Set.of("ouf:name","ouf:address"),"assertion://whole/1")));
    assertThat(backfill.rebuild(two).indexedObjects()).isEqualTo(5);
    var incoming=new GovernedIdentityEngine.Subject("default","ouf:Road","registry",Map.of(
        "ouf:name",new GovernedIdentityEngine.Value("ouf:name@semantic://publication/1",
            "Brand new","handoff://incoming/name"),
        "ouf:address",new GovernedIdentityEngine.Value("ouf:address@semantic://publication/1",
            "Via Roma","handoff://incoming/address")));
    var retrieved=candidates.retrieve(two,incoming);
    assertThat(retrieved.complete()).isTrue();
    assertThat(retrieved.rows()).isEmpty();
    assertThat(new GovernedIdentityEngine().decide(two,incoming,retrieved).outcome())
        .isEqualTo(GovernedIdentityEngine.Outcome.NEW_OBJECT);
  }
  private void clearObjects(){db.sql("truncate table ouf_udp.property_conflict,ouf_udp.property_value,ouf_udp.materialization_observation,ouf_udp.property_contribution,ouf_udp.object_revision,ouf_udp.resolution_issue,ouf_udp.resolution_decision,ouf_udp.source_binding,ouf_udp.urban_object,ouf_udp.materialization_job,ouf_udp.handoff_event,ouf_udp.handoff_intake restart identity cascade").update();}
  private GovernedIdentityEngine.Policy policy(){return new GovernedIdentityEngine.Policy("policy://identity/1","1","default","ouf:Road","registry",1,true,
        List.of(new GovernedIdentityEngine.Signal("ouf:name","ouf:name@semantic://publication/1",
            GovernedIdentityEngine.ComparatorKind.TEXT_V1,false,false,"assertion://name/1")),
        List.of(new GovernedIdentityEngine.SufficientRule("name",Set.of("ouf:name"),"assertion://rule/1")));}
  private GovernedIdentityEngine.Subject subject(String value){return new GovernedIdentityEngine.Subject("default","ouf:Road","registry",Map.of("ouf:name",
      new GovernedIdentityEngine.Value("ouf:name@semantic://publication/1",value,"handoff://probe")));}
  private UUID materialize(String id,String name){
    Map<String,Object> envelope=envelope(id,name);
    intake.accept(envelope);
    UUID object=resolver.resolve("candidate-"+id,envelope,new UdpPorts.ResolutionProfile("CANONICAL_KEY","1","policy://resolution/1","ouf:Road","code","code")).targetUrbanObjectId();
    materializer.materialize("candidate-"+id,object,envelope,new UdpPorts.MaterializationProfile("policy://authority/1",
        List.of(new UdpPorts.PropertyRule("name","ouf:name","string","OPEN",List.of("registry")))));
    return object;
  }
  private UUID materializeWithAddress(String id,String name,String address){
    Map<String,Object> envelope=envelope(id,name);
    envelope.put("canonicalPayload",Map.of("code",id,"name",name,"address",address));
    intake.accept(envelope);
    UUID object=resolver.resolve("candidate-"+id,envelope,new UdpPorts.ResolutionProfile("CANONICAL_KEY","1","policy://resolution/1","ouf:Road","code","code")).targetUrbanObjectId();
    materializer.materialize("candidate-"+id,object,envelope,new UdpPorts.MaterializationProfile("policy://authority/1",
        List.of(new UdpPorts.PropertyRule("name","ouf:name","string","OPEN",List.of("registry")),
            new UdpPorts.PropertyRule("address","ouf:address","string","OPEN",List.of("registry")))));
    return object;
  }
  private Map<String,Object> envelope(String id,String name){return new LinkedHashMap<>(Map.ofEntries(
        Map.entry("handoffId","candidate-"+id),Map.entry("ingestionRunId","run-1"),Map.entry("ingestionId","ing-"+id),
        Map.entry("sourceIdentity",Map.of("sourceId","registry","typeCode","ROAD","sourceObjectId",id,"observedAt","2026-09-12T00:00:00Z")),
        Map.entry("operation","UPSERT"),Map.entry("canonicalPayload",Map.of("code",id,"name",name)),
        Map.entry("rawObjectRef","raw://"+id),Map.entry("contractRefs",Map.of("sourceSchemaRef","schema://road/1","bundleRef","bundle://road/1","semanticPublicationSetRef","semantic://publication/1","adapterProfileRef","adapter://rest/1")),
        Map.entry("lineageId","lineage-"+id),Map.entry("contentHash","sha256:"+id),
        Map.entry("acquiredAt","2026-09-12T00:00:00Z"),Map.entry("changeRepresentation",Map.of("mode","FULL_SNAPSHOT"))));}
  private static String required(String n){String value=System.getenv(n);if(value==null)throw new IllegalStateException(n+" required");return value;}
}
