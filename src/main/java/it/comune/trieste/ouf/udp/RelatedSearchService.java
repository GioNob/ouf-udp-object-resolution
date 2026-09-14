package it.comune.trieste.ouf.udp;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RelatedSearchService {
  private static final int MAX_TARGET_TYPES = 10;
  private static final int MAX_RESULTS = 200;
  private final JdbcClient db;
  private final QueryBudgetStore budgets;

  public RelatedSearchService(JdbcClient db, QueryBudgetStore budgets) {
    this.db = db;
    this.budgets = budgets;
  }

  @Transactional
  public Result search(Request request, ServingAuthorizationContext auth) {
    auth.require("urban.object.related_search");
    LogicalQueryPlan plan = translate(request);
    ensureVisibleAnchor(plan, auth);
    budgets.consume(auth, "GRAPH", 1, plan.maxResults(), plan.maxResults(),
        plan.maxResults() * 192L, 2000);
    db.sql("set local statement_timeout='2000ms'").update();
    if (auth.allowedDataLabels().isEmpty()) return new Result(List.of(), false, plan);

    List<Hit> rows = switch (plan.direction()) {
      case OUTBOUND -> outbound(plan, auth);
      case INBOUND -> inbound(plan, auth);
    };
    boolean truncated = rows.size() > plan.maxResults();
    if (truncated) rows = rows.subList(0, plan.maxResults());
    return new Result(rows, truncated, plan);
  }

  LogicalQueryPlan translate(Request request) {
    if (request == null || request.anchorObjectId() == null
        || invalidIri(request.anchorType()) || invalidIri(request.relationIri())
        || request.direction() == null || request.targetTypes() == null
        || request.targetTypes().isEmpty() || request.targetTypes().size() > MAX_TARGET_TYPES
        || request.targetTypes().stream().anyMatch(RelatedSearchService::invalidIri)
        || request.maxResults() < 1 || request.maxResults() > MAX_RESULTS) {
      throw new IllegalArgumentException("UDP_RELATED_SEARCH_INPUT_INVALID");
    }
    List<String> targetTypes = request.targetTypes().stream().distinct().sorted().toList();
    return new LogicalQueryPlan(request.anchorObjectId(), request.anchorType(),
        request.relationIri(), request.direction(), targetTypes, request.maxResults(),
        "FIXED_RELATIONSHIP_JOIN");
  }

  private void ensureVisibleAnchor(LogicalQueryPlan plan, ServingAuthorizationContext auth) {
    long visible = db.sql("select count(*) from ouf_udp.urban_object where urban_object_id=:id and canonical_type=:type and tenant_id=:tenant and status='ACTIVE'")
        .param("id", plan.anchorObjectId()).param("type", plan.anchorType())
        .param("tenant", auth.tenantId()).query(Long.class).single();
    if (visible == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "UDP_RELATED_SEARCH_ANCHOR_NOT_VISIBLE");
    }
  }

  private List<Hit> outbound(LogicalQueryPlan plan, ServingAuthorizationContext auth) {
    return db.sql("select r.relationship_id,r.target_object_id object_id,o.canonical_type from ouf_udp.urban_relationship r join ouf_udp.relationship_revision v on v.relationship_revision_id=r.current_revision_id join ouf_udp.urban_object o on o.urban_object_id=r.target_object_id where r.source_object_id=:anchor and r.relation_iri=:relation and r.status='ACTIVE' and o.status='ACTIVE' and o.tenant_id=:tenant and o.canonical_type in (:types) and v.access_label in (:labels) order by o.canonical_type,o.urban_object_id limit :limit")
        .param("anchor", plan.anchorObjectId()).param("relation", plan.relationIri())
        .param("tenant", auth.tenantId()).param("types", plan.targetTypes())
        .param("labels", auth.allowedDataLabels()).param("limit", plan.maxResults() + 1)
        .query((rs, row) -> new Hit(rs.getObject("relationship_id", UUID.class),
            rs.getObject("object_id", UUID.class), rs.getString("canonical_type"))).list();
  }

  private List<Hit> inbound(LogicalQueryPlan plan, ServingAuthorizationContext auth) {
    return db.sql("select r.relationship_id,r.source_object_id object_id,o.canonical_type from ouf_udp.urban_relationship r join ouf_udp.relationship_revision v on v.relationship_revision_id=r.current_revision_id join ouf_udp.urban_object o on o.urban_object_id=r.source_object_id where r.target_object_id=:anchor and r.relation_iri=:relation and r.status='ACTIVE' and o.status='ACTIVE' and o.tenant_id=:tenant and o.canonical_type in (:types) and v.access_label in (:labels) order by o.canonical_type,o.urban_object_id limit :limit")
        .param("anchor", plan.anchorObjectId()).param("relation", plan.relationIri())
        .param("tenant", auth.tenantId()).param("types", plan.targetTypes())
        .param("labels", auth.allowedDataLabels()).param("limit", plan.maxResults() + 1)
        .query((rs, row) -> new Hit(rs.getObject("relationship_id", UUID.class),
            rs.getObject("object_id", UUID.class), rs.getString("canonical_type"))).list();
  }

  private static boolean invalidIri(String value) {
    return value == null || value.isBlank() || value.length() > 256 || value.contains("*")
        || value.chars().anyMatch(Character::isWhitespace);
  }

  public enum Direction { INBOUND, OUTBOUND }
  public record Request(UUID anchorObjectId, String anchorType, String relationIri,
                        Direction direction, List<String> targetTypes, int maxResults) {}
  public record LogicalQueryPlan(UUID anchorObjectId, String anchorType, String relationIri,
                                 Direction direction, List<String> targetTypes, int maxResults,
                                 String physicalStrategy) {
    public LogicalQueryPlan { targetTypes = List.copyOf(targetTypes); }
  }
  public record Hit(UUID relationshipId, UUID urbanObjectId, String canonicalType) {}
  public record Result(List<Hit> hits, boolean truncated, LogicalQueryPlan plan) {
    public Result { hits = List.copyOf(hits); }
  }
}
