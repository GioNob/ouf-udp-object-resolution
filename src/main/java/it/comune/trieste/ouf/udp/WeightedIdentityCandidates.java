package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Candidate generation is a bounded indexed lookup, never an all-pairs comparison. */
final class WeightedIdentityCandidates {
  private final JdbcClient db; private final ObjectMapper json;
  WeightedIdentityCandidates(JdbcClient db, ObjectMapper json) { this.db = db; this.json = json; }

  WeightedIdentity.Outcome resolve(Map<String,Object> incoming, UdpPorts.ResolutionProfile profile,
      String tenant, GovernedCrsTransform.Result geometry) {
    var policy = profile.weighted(); var blocking = new TreeMap<String,Object>();
    for (String property : policy.blockingProperties()) {
      Object value = incoming.get(property);
      if (value == null || value instanceof Map || value instanceof Collection || String.valueOf(value).isBlank())
        return new WeightedIdentity.Outcome("REVIEW_REQUIRED", null, "BLOCKING_EVIDENCE_MISSING", List.of());
      blocking.put(property, value);
    }
    boolean spatial = policy.blockingDistanceMeters() != null || policy.signals().stream().anyMatch(WeightedIdentity.Signal::spatial);
    if (spatial && (geometry == null || !"OK".equals(geometry.status())))
      return new WeightedIdentity.Outcome("REVIEW_REQUIRED", null, "SPATIAL_EVIDENCE_MISSING", List.of());
    String query = "select o.urban_object_id,c.canonical_payload::text payload";
    if (spatial) query += ",ST_Distance(g.geometry::geography,s.geom::geography) distance," +
        "case when ST_Dimension(g.geometry)=2 and ST_Dimension(s.geom)=2 then " +
        "ST_Area(ST_Intersection(g.geometry,s.geom)::geography)/nullif(ST_Area(ST_Union(g.geometry,s.geom)::geography),0) else null end overlap";
    query += " from ouf_udp.urban_object o join ouf_udp.urban_object_current_state c on c.urban_object_id=o.urban_object_id";
    if (spatial) query += " join ouf_udp.urban_geometry_current gc on gc.urban_object_id=o.urban_object_id join ouf_udp.urban_geometry g on g.geometry_revision_id=gc.geometry_revision_id cross join (select ST_GeomFromEWKB(decode(:geometry,'hex')) geom) s";
    query += " where o.tenant_id=:tenant and o.canonical_type=:type and o.status='ACTIVE' and c.canonical_payload @> cast(:blocking as jsonb)";
    if (policy.blockingDistanceMeters() != null) query += " and ST_DWithin(g.geometry::geography,s.geom::geography,:radius)";
    query += " order by o.urban_object_id limit :limit";
    try {
      var sql = db.sql(query).param("tenant", tenant).param("type", profile.canonicalType())
          .param("blocking", json.writeValueAsString(blocking)).param("limit", policy.maxCandidates() + 1);
      if (spatial) sql = sql.param("geometry", geometry.servingEwkb());
      if (policy.blockingDistanceMeters() != null) sql = sql.param("radius", policy.blockingDistanceMeters());
      var rows = sql.query().listOfRows();
      // The over-limit result is never ranked or partially accepted.
      if (rows.size() > policy.maxCandidates()) return WeightedIdentity.decide(List.of(), policy, true);
      var candidates = new ArrayList<WeightedIdentity.Candidate>();
      for (var row : rows) {
        Map<String,Object> existing = json.readValue(String.valueOf(row.get("payload")), new TypeReference<>() {});
        Map<String,Double> facts = new HashMap<>();
        if (row.get("distance") instanceof Number n) facts.put("DISTANCE", n.doubleValue());
        if (row.get("overlap") instanceof Number n) facts.put("OVERLAP", n.doubleValue());
        candidates.add(WeightedIdentity.score((UUID)row.get("urban_object_id"), incoming, existing, facts, policy));
      }
      return WeightedIdentity.decide(candidates, policy, false);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("UDP_IDENTITY_EVIDENCE_INVALID", e); }
  }
}
