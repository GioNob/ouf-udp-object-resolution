package it.comune.trieste.ouf.udp;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LakeMaintenanceRepository {
  private final JdbcClient db;
  public LakeMaintenanceRepository(JdbcClient db){this.db=db;}

  @Transactional public Optional<Claim> claim(String worker,Duration lease){
    return db.sql("with candidate as(select * from ouf_udp.lake_maintenance_schedule where (state='READY' and next_run_at<=transaction_timestamp()) or (state='RUNNING' and lease_until<transaction_timestamp()) order by next_run_at,task_name for update skip locked limit 1), expired as(insert into ouf_udp.lake_maintenance_attempt(attempt_id,task_name,lease_generation,worker_id,state,safe_error_code,started_at) select gen_random_uuid(),task_name,lease_generation,lease_owner,'LEASE_LOST','UDP_LAKE_MAINTENANCE_LEASE_EXPIRED',last_started_at from candidate where state='RUNNING' returning task_name) update ouf_udp.lake_maintenance_schedule s set state='RUNNING',lease_owner=:worker,lease_until=transaction_timestamp()+(:lease*interval '1 millisecond'),lease_generation=s.lease_generation+1,last_started_at=transaction_timestamp(),updated_at=transaction_timestamp() from candidate c where s.task_name=c.task_name returning s.task_name,s.lease_generation")
      .param("worker",worker).param("lease",lease.toMillis()).query((rs,n)->new Claim(rs.getString(1),worker,rs.getLong(2))).optional();
  }

  @Transactional public void succeed(Claim claim,LakeLifecycleService.ScanResult result){
    int schedule=db.sql("update ouf_udp.lake_maintenance_schedule set state='READY',next_run_at=transaction_timestamp()+(interval_seconds*interval '1 second'),lease_owner=null,lease_until=null,consecutive_failures=0,last_safe_error_code=null,last_scan_id=:scan,last_completed_at=transaction_timestamp(),updated_at=transaction_timestamp() where task_name=:task and state='RUNNING' and lease_owner=:worker and lease_generation=:generation").param("scan",result.scanId()).param("task",claim.task()).param("worker",claim.worker()).param("generation",claim.generation()).update();
    if(schedule!=1)throw new IllegalStateException("UDP_LAKE_MAINTENANCE_LEASE_LOST");
    db.sql("insert into ouf_udp.lake_maintenance_attempt(attempt_id,task_name,lease_generation,worker_id,state,scan_id,missing_count,checksum_mismatch_count,orphan_count,started_at) select gen_random_uuid(),task_name,:generation,:worker,'SUCCEEDED',:scan,:missing,:mismatch,:orphan,last_started_at from ouf_udp.lake_maintenance_schedule where task_name=:task").param("generation",claim.generation()).param("worker",claim.worker()).param("scan",result.scanId()).param("missing",result.missing()).param("mismatch",result.checksumMismatch()).param("orphan",result.orphan()).param("task",claim.task()).update();
  }

  @Transactional public void fail(Claim claim,String safeCode){
    String code=safe(safeCode);
    int schedule=db.sql("update ouf_udp.lake_maintenance_schedule set state='READY',next_run_at=transaction_timestamp()+(least(3600,power(2,least(consecutive_failures,10))::integer*60)*interval '1 second'),lease_owner=null,lease_until=null,consecutive_failures=consecutive_failures+1,last_safe_error_code=:code,updated_at=transaction_timestamp() where task_name=:task and state='RUNNING' and lease_owner=:worker and lease_generation=:generation").param("code",code).param("task",claim.task()).param("worker",claim.worker()).param("generation",claim.generation()).update();
    if(schedule!=1)throw new IllegalStateException("UDP_LAKE_MAINTENANCE_LEASE_LOST");
    db.sql("insert into ouf_udp.lake_maintenance_attempt(attempt_id,task_name,lease_generation,worker_id,state,safe_error_code,started_at) select gen_random_uuid(),task_name,:generation,:worker,'FAILED',:code,last_started_at from ouf_udp.lake_maintenance_schedule where task_name=:task").param("generation",claim.generation()).param("worker",claim.worker()).param("code",code).param("task",claim.task()).update();
  }
  private static String safe(String value){String normalized=value==null?"UDP_LAKE_MAINTENANCE_FAILED":value.replaceAll("[^A-Z0-9_]","_");return normalized.substring(0,Math.min(normalized.length(),120));}
  public record Claim(String task,String worker,long generation){}
}
