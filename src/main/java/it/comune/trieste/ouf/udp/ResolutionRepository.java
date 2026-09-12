package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ResolutionRepository {
  private final JdbcClient db;private final ObjectMapper json;public ResolutionRepository(JdbcClient db,ObjectMapper json){this.db=db;this.json=json;}
  @Transactional public Optional<Claim> claim(String worker,Duration lease){
    return db.sql("with candidate as (select job_id from ouf_udp.materialization_job where state='READY' or (state='RUNNING' and lease_until<transaction_timestamp()) order by created_at for update skip locked limit 1) update ouf_udp.materialization_job j set state='RUNNING',claimed_by=:w,lease_until=transaction_timestamp()+(:m*interval '1 millisecond'),attempts=attempts+1,updated_at=transaction_timestamp() from candidate c,handoff_intake h where j.job_id=c.job_id and h.handoff_id=j.handoff_id returning j.job_id,j.handoff_id,h.type_code,h.payload_json::text")
      .param("w",worker).param("m",lease.toMillis()).query((r,n)->new Claim(r.getObject(1,UUID.class),r.getString(2),r.getString(3),read(r.getString(4)),worker)).optional();
  }
  @Transactional public void complete(Claim claim){int n=db.sql("update ouf_udp.materialization_job set state='SUCCEEDED',claimed_by=null,lease_until=null,updated_at=transaction_timestamp() where job_id=:j and state='RUNNING' and claimed_by=:w").param("j",claim.jobId()).param("w",claim.worker()).update();if(n!=1)throw new IllegalStateException("UDP_RESOLUTION_LEASE_LOST");db.sql("update ouf_udp.handoff_intake set state='PROCESSED',processed_at=transaction_timestamp() where handoff_id=:h and state in('DURABLE','PROCESSING')").param("h",claim.handoffId()).update();event(claim.handoffId(),"RESOLUTION_COMPLETED");}
  @Transactional public void fail(Claim claim,String safeCode){db.sql("update ouf_udp.materialization_job set state='FAILED',claimed_by=null,lease_until=null,updated_at=transaction_timestamp() where job_id=:j and state='RUNNING' and claimed_by=:w").param("j",claim.jobId()).param("w",claim.worker()).update();db.sql("update ouf_udp.handoff_intake set state='FAILED',failure_code=:c where handoff_id=:h").param("c",safe(safeCode)).param("h",claim.handoffId()).update();event(claim.handoffId(),"RESOLUTION_FAILED");}
  private void event(String handoff,String type){db.sql("insert into ouf_udp.handoff_event(event_id,handoff_id,event_type) values(gen_random_uuid(),:h,:t)").param("h",handoff).param("t",type).update();}
  private Map<String,Object> read(String value){try{return json.readValue(value,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException("UDP_STORED_JSON_INVALID",e);}}
  private static String safe(String value){return value!=null&&value.matches("UDP_[A-Z0-9_]{1,70}")?value:"UDP_RESOLUTION_FAILED";}
  public record Claim(UUID jobId,String handoffId,String typeCode,Map<String,Object> payload,String worker){}
}
