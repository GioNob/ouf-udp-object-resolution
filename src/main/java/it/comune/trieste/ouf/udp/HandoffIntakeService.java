package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HandoffIntakeService {
  private final JdbcClient db;private final ObjectMapper json;private final FrozenContractValidator contracts;
  public HandoffIntakeService(JdbcClient db,ObjectMapper json,FrozenContractValidator contracts){this.db=db;this.json=json;this.contracts=contracts;}
  @Transactional public Receipt accept(Map<String,Object> payload){
    contracts.validate("/contracts/rc3/ingestion/handoff-payload-v1.json",payload);String id=text(payload,"handoffId"),hash=text(payload,"contentHash");Map<String,Object> identity=object(payload,"sourceIdentity");String receipt="udp://handoffs/"+id;
    int inserted=db.sql("insert into ouf_udp.handoff_intake(handoff_id,ingestion_run_id,ingestion_id,source_id,type_code,source_object_id,content_hash,payload_json,receipt_ref) values(:h,:r,:i,:s,:t,:o,:c,cast(:p as jsonb),:x) on conflict(handoff_id) do nothing")
      .param("h",id).param("r",text(payload,"ingestionRunId")).param("i",text(payload,"ingestionId")).param("s",text(identity,"sourceId")).param("t",text(identity,"typeCode")).param("o",text(identity,"sourceObjectId")).param("c",hash).param("p",write(payload)).param("x",receipt).update();
    if(inserted==0){Map<String,Object> existing=db.sql("select content_hash,receipt_ref from ouf_udp.handoff_intake where handoff_id=:h").param("h",id).query().singleRow();if(!hash.equals(existing.get("content_hash")))throw new ResponseStatusException(HttpStatus.CONFLICT,"UDP_HANDOFF_IDEMPOTENCY_CONFLICT");return new Receipt(String.valueOf(existing.get("receipt_ref")),true,true);}
    db.sql("insert into ouf_udp.materialization_job(job_id,handoff_id) values(gen_random_uuid(),:h)").param("h",id).update();event(id,"HANDOFF_DURABLE",Map.of("receiptRef",receipt));return new Receipt(receipt,true,false);
  }
  public Map<String,Object> handoff(String id){return db.sql("select handoff_id,ingestion_run_id,ingestion_id,source_id,type_code,source_object_id,content_hash,receipt_ref,state,received_at,processed_at,failure_code from ouf_udp.handoff_intake where handoff_id=:h").param("h",id).query().listOfRows().stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"UDP_HANDOFF_NOT_FOUND"));}
  private void event(String id,String type,Map<String,Object> detail){db.sql("insert into ouf_udp.handoff_event(event_id,handoff_id,event_type,safe_detail) values(gen_random_uuid(),:h,:t,cast(:d as jsonb))").param("h",id).param("t",type).param("d",write(detail)).update();}
  private String write(Object v){try{return json.writeValueAsString(v);}catch(JsonProcessingException e){throw new IllegalArgumentException("UDP_JSON_INVALID",e);}}
  @SuppressWarnings("unchecked") static Map<String,Object> object(Map<String,Object> map,String key){return (Map<String,Object>)map.get(key);}static String text(Map<String,Object> map,String key){return String.valueOf(map.get(key));}
  public record Receipt(String receiptRef,boolean durable,boolean duplicate){}
}
