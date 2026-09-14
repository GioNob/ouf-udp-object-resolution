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
class RelatedSearchRuntimeTest {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> required("OUF_UDP_DB_URL"));
    registry.add("spring.datasource.username", () -> required("OUF_UDP_DB_USER"));
    registry.add("spring.datasource.password", () -> required("OUF_UDP_DB_PASSWORD"));
  }

  @Autowired RelatedSearchService related;
  @Autowired JdbcClient db;
  private ServingAuthorizationContext auth;

  @BeforeEach
  void clean() {
    db.sql("truncate table ouf_udp.query_budget,ouf_udp.serving_access_audit,ouf_udp.merge_resolution_issue,ouf_udp.merge_property_contribution_link,ouf_udp.human_resolution_decision,ouf_udp.split_resolution_issue,ouf_udp.relationship_identity_history,ouf_udp.source_binding_history,ouf_udp.object_identity_history,ouf_udp.governance_audit,ouf_udp.governance_plan,ouf_udp.spatial_resolution_issue,ouf_udp.urban_geometry_current,ouf_udp.urban_geometry,ouf_udp.relationship_issue,ouf_udp.relationship_revision,ouf_udp.relationship_contribution,ouf_udp.urban_relationship,ouf_udp.property_conflict,ouf_udp.property_value,ouf_udp.materialization_observation,ouf_udp.property_contribution,ouf_udp.object_revision,ouf_udp.resolution_issue,ouf_udp.resolution_decision,ouf_udp.source_binding,ouf_udp.urban_object,ouf_udp.materialization_job,ouf_udp.handoff_event,ouf_udp.handoff_intake restart identity cascade").update();
    auth = context("corr-related");
  }

  @Test
  void typedPatternUsesFixedJoinAndFiltersTenantLabelAndTargetType() {
    UUID street = object("street", "ouf:Street", "default");
    UUID dehor = object("dehor", "ouf:Dehor", "default");
    UUID worksite = object("worksite", "ouf:Worksite", "default");
    UUID restricted = object("pole", "ouf:LightingPole", "default");
    UUID otherTenant = object("other", "ouf:Dehor", "other");
    edge("edge-1", dehor, street, "ouf:locatedOn", "OPEN");
    edge("edge-2", worksite, street, "ouf:locatedOn", "OPEN");
    edge("edge-3", restricted, street, "ouf:locatedOn", "RESTRICTED");
    edge("edge-4", otherTenant, street, "ouf:locatedOn", "OPEN");

    var request = new RelatedSearchService.Request(street, "ouf:Street", "ouf:locatedOn",
        RelatedSearchService.Direction.INBOUND, List.of("ouf:Dehor", "ouf:Worksite"), 10);
    var result = related.search(request, auth);

    assertThat(result.hits()).extracting(RelatedSearchService.Hit::urbanObjectId)
        .containsExactlyInAnyOrder(dehor, worksite).doesNotContain(restricted, otherTenant);
    assertThat(result.plan().physicalStrategy()).isEqualTo("FIXED_RELATIONSHIP_JOIN");
    assertThat(result.plan().targetTypes()).containsExactly("ouf:Dehor", "ouf:Worksite");
  }

  @Test
  void inputContractContainsNoQueryLanguageOrPhysicalPlanControl() {
    assertThat(Arrays.stream(RelatedSearchService.Request.class.getRecordComponents())
        .map(component -> component.getName()).toList())
        .containsExactly("anchorObjectId", "anchorType", "relationIri", "direction",
            "targetTypes", "maxResults")
        .noneMatch(name -> Set.of("sql", "jpql", "cte", "cypher", "sparql", "queryLanguage",
            "physicalPlan", "joinOrder", "indexHint").contains(name));
  }

  @Test
  void invisibleOrTypeMismatchedAnchorIsRejectedBeforeBudget() {
    UUID street = object("street", "ouf:Street", "default");
    var request = new RelatedSearchService.Request(street, "ouf:Area", "ouf:locatedOn",
        RelatedSearchService.Direction.INBOUND, List.of("ouf:Dehor"), 10);
    assertThatThrownBy(() -> related.search(request, auth))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404")
        .hasMessageNotContaining(street.toString());
    assertThat(db.sql("select count(*) from ouf_udp.query_budget").query(Long.class).single())
        .isZero();
  }

  @Test
  void capabilityAndLimitsAreFailClosed() {
    UUID street = object("street", "ouf:Street", "default");
    var request = new RelatedSearchService.Request(street, "ouf:Street", "ouf:locatedOn",
        RelatedSearchService.Direction.INBOUND, List.of("ouf:Dehor"), 201);
    assertThatThrownBy(() -> related.search(request, auth)).hasMessageContaining("INPUT_INVALID");
    var denied = new ServingAuthorizationContext("AI_AGENT", "agent", "default", Set.of(),
        Set.of("OPEN"), "authz://related", "corr-denied");
    assertThatThrownBy(() -> related.search(new RelatedSearchService.Request(street, "ouf:Street",
        "ouf:locatedOn", RelatedSearchService.Direction.INBOUND, List.of("ouf:Dehor"), 10), denied))
        .isInstanceOf(SecurityException.class).hasMessageContaining("AUTHORIZATION_DENIED");
  }

  private UUID object(String key, String type, String tenant) {
    UUID id = UUID.randomUUID();
    db.sql("insert into ouf_udp.urban_object(urban_object_id,canonical_type,canonical_key,match_key,tenant_id) values(:id,:type,:key,:key,:tenant)")
        .param("id", id).param("type", type).param("key", key).param("tenant", tenant).update();
    return id;
  }

  private void edge(String handoff, UUID source, UUID target, String relation, String label) {
    db.sql("insert into ouf_udp.handoff_intake(handoff_id,ingestion_run_id,ingestion_id,source_id,type_code,source_object_id,content_hash,payload_json,receipt_ref) values(:h,'run','ing','related','OBJECT',:h,:h,'{}',:receipt)")
        .param("h", handoff).param("receipt", "udp://" + handoff).update();
    UUID contribution = UUID.randomUUID(), relationship = UUID.randomUUID(), revision = UUID.randomUUID();
    db.sql("insert into ouf_udp.relationship_contribution(contribution_id,handoff_id,source_object_id,relation_iri,source_value,source_value_hash,strategy_ref,policy_ref,access_label,provenance_json) values(:id,:h,:source,:relation,'{}',:h,'MANUAL','policy://related',:label,'{}')")
        .param("id", contribution).param("h", handoff).param("source", source)
        .param("relation", relation).param("label", label).update();
    db.sql("insert into ouf_udp.urban_relationship(relationship_id,source_object_id,relation_iri,target_object_id) values(:id,:source,:relation,:target)")
        .param("id", relationship).param("source", source).param("relation", relation)
        .param("target", target).update();
    db.sql("insert into ouf_udp.relationship_revision(relationship_revision_id,relationship_id,revision_no,contribution_id,evidence_hash,resolution_evidence,access_label) values(:id,:relationship,1,:contribution,:hash,'{}',:label)")
        .param("id", revision).param("relationship", relationship).param("contribution", contribution)
        .param("hash", "hash-" + handoff).param("label", label).update();
    db.sql("update ouf_udp.urban_relationship set current_revision_id=:revision where relationship_id=:relationship")
        .param("revision", revision).param("relationship", relationship).update();
  }

  private static ServingAuthorizationContext context(String correlation) {
    return new ServingAuthorizationContext("AI_AGENT", "agent", "default",
        Set.of("urban.object.related_search"), Set.of("OPEN", "ANONYMOUS"),
        "authz://related", correlation);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null) throw new IllegalStateException(name + " required");
    return value;
  }
}
