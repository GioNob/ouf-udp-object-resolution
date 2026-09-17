package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Exact value, classification, source identity and validity; observation time is not a new rule. */
final class PropertyEvidence {
  private PropertyEvidence() {}
  static List<Map<String,Object>> top(JdbcClient db, UUID object, UdpPorts.PropertyRule rule) {
    var rows=db.sql("select distinct on (source_id) contribution_id,value_json::text value_json,value_hash,access_label,source_id,authority_rank,provenance_json::text provenance, ((provenance_json->'sourceIdentity')-'observedAt')::text source_identity from ouf_udp.property_contribution where urban_object_id=:u and property_iri=:p order by source_id,observed_at desc nulls last,created_at desc,contribution_id")
        .param("u",object).param("p",rule.propertyIri()).query().listOfRows();
    for(var row:rows){int rank=rule.authorityOrder().indexOf(String.valueOf(row.get("source_id")));row.put("authority_rank",rank<0?10000:rank);}
    int best=rows.stream().mapToInt(r->((Number)r.get("authority_rank")).intValue()).min().orElse(10000);
    return rows.stream().filter(r->((Number)r.get("authority_rank")).intValue()==best).toList();
  }
  static String item(Map<String,Object> row) {
    // PostgreSQL jsonb provides a stable serialized identity independent of input key order.
    return hash(new TreeMap<>(Map.of("source",row.get("source_id"),"identity",row.get("source_identity"),"value",row.get("value_hash"),"label",row.get("access_label"))));
  }
  static String set(List<Map<String,Object>> rows,UdpPorts.PropertyRule rule){return hash(Map.of("rule",rule,"contributions",rows.stream().map(PropertyEvidence::item).sorted().toList()));}
  static String policy(UdpPorts.PropertyRule rule){return hash(rule);}
  private static String hash(Object value){try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new ObjectMapper().writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
