package it.comune.trieste.ouf.udp;

import java.util.Map;
import java.net.URI;

/** Coordinates come from the verified publication, never from an error body. */
final class SemanticReferenceFailure extends IllegalArgumentException {
  // Both owner views project only the allowlisted coordinates of the current quarantine.
  static final String RETAINED_DETAIL_SQL = """
      (select jsonb_build_object('index',e.safe_detail->'index','semanticId',e.safe_detail->'semanticId',
          'revisionId',e.safe_detail->'revisionId')::text from ouf_udp.handoff_event e
       where e.handoff_id=j.handoff_id and j.state='QUARANTINED'
         and e.event_type='REFERENCE_INTEGRITY_QUARANTINED' and e.safe_detail->>'safeFailureCode'=j.safe_failure_code
         and jsonb_exists(e.safe_detail,'semanticId') order by e.created_at desc,e.event_id desc limit 1)
      """;
  final Map<String,Object> detail;
  SemanticReferenceFailure(String code, int index, Map<String,Object> pin) {
    super(code);
    detail = Map.of("index", index, "semanticId", safeId((String)pin.get("semanticId")), "revisionId", pin.get("revisionId"));
  }
  private static String safeId(String id) {
    // Only ordinary semantic IRIs go into operator evidence. A non-displayable pin
    // remains identifiable by its exact digest, global index and revision.
    try {
      URI iri=URI.create(id);
      if(id.length()<=2048 && iri.isAbsolute() && iri.getRawUserInfo()==null
          && !id.contains("?") && id.codePoints().noneMatch(Character::isISOControl))return id;
    } catch(IllegalArgumentException malformed) { /* retain its digest below */ }
    return HistoricalContractCatalog.hash(id);
  }
}
