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
class ScopedIdentityCandidateRepositoryRuntimeTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",()->required("OUF_UDP_DB_URL"));
    r.add("spring.datasource.username",()->required("OUF_UDP_DB_USER"));
    r.add("spring.datasource.password",()->required("OUF_UDP_DB_PASSWORD"));
  }
  @Autowired HandoffIntakeService intake;
  @Autowired ObjectResolutionService resolver;
  @Autowired CanonicalMaterializer materializer;
  @Autowired ScopedIdentityCandidateRepository candidates;
  @Autowired JdbcClient db;

  private final UdpPorts.ResolutionProfile legacy=new UdpPorts.ResolutionProfile("CANONICAL_KEY","1","policy://resolution/1","ouf:Road","code","code");
  private final UdpPorts.MaterializationProfile authority=new UdpPorts.MaterializationProfile("policy://authority/1",
      List.of(new UdpPorts.PropertyRule("name","ouf:name","string","OPEN",List.of("registry"))));

  @BeforeEach void clean(){db.sql("truncate table ouf_udp.property_conflict,ouf_udp.property_value,ouf_udp.materialization_observation,ouf_udp.property_contribution,ouf_udp.object_revision,ouf_udp.resolution_issue,ouf_udp.resolution_decision,ouf_udp.source_binding,ouf_udp.urban_object,ouf_udp.materialization_job,ouf_udp.handoff_event,ouf_udp.handoff_intake restart identity cascade").update();}

  @Test void readsCurrentPublishedValuesAcrossEntireTenantClassAndBoundsOverflow(){
    UUID first=materialize("one","Alpha"),second=materialize("two","Beta");
    var full=candidates.retrieve(policy(2));
    assertThat(full.complete()).isTrue();
    assertThat(full.coverageRef()).startsWith("postgres-snapshot://");
    assertThat(full.rows()).extracting(GovernedIdentityEngine.Candidate::objectId).containsExactly(first,second);
    assertThat(full.rows().getFirst().values().get("ouf:name"))
        .satisfies(value->{assertThat(value.semanticRef()).isEqualTo("ouf:name@semantic://publication/1");
          assertThat(value.raw()).isEqualTo("Alpha");assertThat(value.provenanceRef()).startsWith("contribution://");});
    var decision=new GovernedIdentityEngine().decide(policy(2),
        new GovernedIdentityEngine.Subject("default","ouf:Road","registry",Map.of("ouf:name",
            new GovernedIdentityEngine.Value("ouf:name@semantic://publication/1","Alpha","handoff://probe"))),full);
    assertThat(decision.outcome()).isEqualTo(GovernedIdentityEngine.Outcome.REVIEW_REQUIRED);
    assertThat(decision.assessments()).hasSize(2);
    var bounded=candidates.retrieve(policy(1));
    assertThat(bounded.complete()).isFalse();
    assertThat(new GovernedIdentityEngine().decide(policy(1),
        new GovernedIdentityEngine.Subject("default","ouf:Road","registry",Map.of()),bounded).outcome())
        .isEqualTo(GovernedIdentityEngine.Outcome.RESOLUTION_TOO_BROAD);
    assertThat(candidates.retrieve(new GovernedIdentityEngine.Policy("policy://identity/1","1","other","ouf:Road","registry",2,false,
        policy(2).signals(),policy(2).sufficientRules())).rows()).isEmpty();
  }

  private UUID materialize(String id,String name){
    Map<String,Object> envelope=new LinkedHashMap<>(Map.ofEntries(
        Map.entry("handoffId","candidate-"+id),Map.entry("ingestionRunId","run-1"),Map.entry("ingestionId","ing-"+id),
        Map.entry("sourceIdentity",Map.of("sourceId","registry","typeCode","ROAD","sourceObjectId",id,"observedAt","2026-09-12T00:00:00Z")),
        Map.entry("operation","UPSERT"),Map.entry("canonicalPayload",Map.of("code",id,"name",name)),
        Map.entry("rawObjectRef","raw://"+id),Map.entry("contractRefs",Map.of("sourceSchemaRef","schema://road/1","bundleRef","bundle://road/1","semanticPublicationSetRef","semantic://publication/1","adapterProfileRef","adapter://rest/1")),
        Map.entry("lineageId","lineage-"+id),Map.entry("contentHash","sha256:"+id),
        Map.entry("acquiredAt","2026-09-12T00:00:00Z"),Map.entry("changeRepresentation",Map.of("mode","FULL_SNAPSHOT"))));
    intake.accept(envelope);
    UUID object=resolver.resolve("candidate-"+id,envelope,legacy).targetUrbanObjectId();
    materializer.materialize("candidate-"+id,object,envelope,authority);
    return object;
  }
  private GovernedIdentityEngine.Policy policy(int limit){return new GovernedIdentityEngine.Policy("policy://identity/1","1","default","ouf:Road","registry",limit,false,
      List.of(new GovernedIdentityEngine.Signal("ouf:name","ouf:name@semantic://publication/1",GovernedIdentityEngine.ComparatorKind.TEXT_V1,false,true,"assertion://name/1")),
      List.of(new GovernedIdentityEngine.SufficientRule("name",Set.of("ouf:name"),"assertion://rule/1")));}
  private static String required(String n){String value=System.getenv(n);if(value==null)throw new IllegalStateException(n+" required");return value;}
}
