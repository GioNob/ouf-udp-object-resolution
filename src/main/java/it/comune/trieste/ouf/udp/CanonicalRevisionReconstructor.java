package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CanonicalRevisionReconstructor {
  private final JdbcClient db;private final ObjectMapper json;public CanonicalRevisionReconstructor(JdbcClient db,ObjectMapper json){this.db=db;this.json=json;}
  @Transactional(readOnly=true) public Map<String,Object> reconstruct(UUID revisionId){List<Row> chain=new ArrayList<>();UUID cursor=revisionId;for(int depth=0;depth<1000;depth++){Row row=row(cursor);chain.add(row);if("FULL_CHECKPOINT".equals(row.representation()))break;if(!"DELTA_PATCH".equals(row.representation())||row.base()==null)throw new IllegalStateException("UDP_REVISION_CHAIN_INVALID");cursor=row.base();}if(chain.size()==1000&&!"FULL_CHECKPOINT".equals(chain.getLast().representation()))throw new IllegalStateException("UDP_REVISION_CHAIN_LIMIT");Collections.reverse(chain);Map<String,Object> state=new TreeMap<>();for(Row row:chain){if("FULL_CHECKPOINT".equals(row.representation()))state.putAll(readMap(row.payload()));else apply(state,readMap(row.delta()));}Row target=chain.getLast();String actual=hash(Map.of("values",state,"labels",readMap(target.labels())));if(!actual.equals(target.canonicalHash()))throw new IllegalStateException("UDP_REVISION_RECONSTRUCTION_HASH_MISMATCH");return Collections.unmodifiableMap(state);}
  private Row row(UUID id){List<Map<String,Object>> rows=db.sql("select revision_id,representation,base_revision_id,canonical_payload::text payload,canonical_delta::text delta,access_labels::text labels,canonical_hash from ouf_udp.object_revision where revision_id=:r").param("r",id).query().listOfRows();if(rows.isEmpty())throw new NoSuchElementException("UDP_REVISION_NOT_FOUND");var r=rows.getFirst();return new Row((UUID)r.get("revision_id"),String.valueOf(r.get("representation")),(UUID)r.get("base_revision_id"),Objects.toString(r.get("payload"),null),Objects.toString(r.get("delta"),null),String.valueOf(r.get("labels")),String.valueOf(r.get("canonical_hash")));}
  @SuppressWarnings("unchecked") private void apply(Map<String,Object> state,Map<String,Object> patch){Object set=patch.get("set"),remove=patch.get("remove");if(!(set instanceof Map<?,?> changes)||!(remove instanceof List<?> removals))throw new IllegalStateException("UDP_REVISION_DELTA_INVALID");changes.forEach((k,v)->state.put(String.valueOf(k),v));removals.forEach(k->state.remove(String.valueOf(k)));}
  private Map<String,Object> readMap(String value){if(value==null)throw new IllegalStateException("UDP_REVISION_CONTENT_MISSING");try{return json.readValue(value,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException("UDP_REVISION_CONTENT_INVALID",e);}}
  private String hash(Object value){try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(canonical(value))));}catch(Exception e){throw new IllegalStateException("UDP_HASH_FAILED",e);}}
  private Object canonical(Object value){if(value instanceof Map<?,?> map){Map<String,Object> sorted=new TreeMap<>();map.forEach((k,v)->sorted.put(String.valueOf(k),canonical(v)));return sorted;}if(value instanceof List<?> list)return list.stream().map(this::canonical).toList();return value;}
  private record Row(UUID id,String representation,UUID base,String payload,String delta,String labels,String canonicalHash){}
}
