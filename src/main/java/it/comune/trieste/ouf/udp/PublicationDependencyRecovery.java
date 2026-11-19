package it.comune.trieste.ouf.udp;

import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable bounded retry of publication GETs; never repeats a canonical mutation. */
@Service
public class PublicationDependencyRecovery {
  private final JdbcClient db;private final int maximumAttempts;private final long maximumMillis;
  public PublicationDependencyRecovery(JdbcClient db,
      @Value("${ouf.udp.publication-recovery.max-attempts:128}") int attempts,
      @Value("${ouf.udp.publication-recovery.max-elapsed-ms:86400000}") long millis){
    if(attempts<1||attempts>128||millis<1||millis>86400000)throw new IllegalArgumentException("UDP_PUBLICATION_RECOVERY_CONFIG_INVALID");
    this.db=db;this.maximumAttempts=attempts;this.maximumMillis=millis;
  }
  @Transactional public boolean beforeAttempt(ResolutionRepository.Claim claim){
    var row=lock(claim);
    if(row.get("dependency_started_at")==null)return true;
    var now=time(row,"observed_at");int attempts=((Number)row.get("dependency_attempts")).intValue();
    if(attempts>=maximumAttempts||!now.isBefore(time(row,"dependency_started_at").plusMillis(maximumMillis))){
      stop(claim,"UDP_PUBLICATION_RETRY_EXHAUSTED",attempts);return false;
    }
    db.sql("update ouf_udp.materialization_job set dependency_attempts=dependency_attempts+1,dependency_cumulative_attempts=dependency_cumulative_attempts+1 where job_id=:j")
      .param("j",claim.jobId()).update();
    return true;
  }
  @Transactional public void defer(ResolutionRepository.Claim claim,PublicationAccessFailure failure){
    if(!failure.retryable())throw new IllegalArgumentException("UDP_NONTRANSIENT_RETRY_DENIED");
    var row=lock(claim);var now=time(row,"observed_at");
    int attempts=((Number)row.get("dependency_attempts")).intValue();
    Instant started;
    if(row.get("dependency_started_at")==null){
      started=time(row,"updated_at");attempts=1;
      db.sql("update ouf_udp.materialization_job set dependency_episode_id=gen_random_uuid(),dependency_started_at=updated_at,dependency_attempts=1,dependency_cumulative_attempts=dependency_cumulative_attempts+1 where job_id=:j")
        .param("j",claim.jobId()).update();
    }else started=time(row,"dependency_started_at");
    var deadline=started.plusMillis(maximumMillis);
    var due=now.plusMillis(delayMillis(attempts,ThreadLocalRandom.current().nextDouble(.8,1.2)));
    if(failure.retryNotBefore()!=null&&failure.retryNotBefore().isAfter(due))due=failure.retryNotBefore();
    if(attempts>=maximumAttempts||!due.isBefore(deadline)){
      stop(claim,"UDP_PUBLICATION_RETRY_EXHAUSTED",attempts);return;
    }
    db.sql("update ouf_udp.materialization_job set state='PAUSED',state_version=state_version+1,claimed_by=null,lease_until=null,next_integrity_check_at=:due,safe_failure_code='UDP_PUBLICATION_UNAVAILABLE',updated_at=transaction_timestamp() where job_id=:j")
      .param("due",OffsetDateTime.ofInstant(due,ZoneOffset.UTC)).param("j",claim.jobId()).update();
    event(claim,"PUBLICATION_DEPENDENCY_RETRY_WAIT",attempts);
  }
  @Transactional public void recovered(ResolutionRepository.Claim claim){
    var row=lock(claim);if(row.get("dependency_started_at")==null)return;
    event(claim,"PUBLICATION_DEPENDENCY_RECOVERED",((Number)row.get("dependency_attempts")).intValue());
    db.sql("update ouf_udp.materialization_job set dependency_episode_id=null,dependency_started_at=null,dependency_attempts=0 where job_id=:j")
      .param("j",claim.jobId()).update();
  }
  private Map<String,Object> lock(ResolutionRepository.Claim claim){
    var rows=db.sql("select *,transaction_timestamp() observed_at from ouf_udp.materialization_job where job_id=:j and state='RUNNING' and claimed_by=:w and state_version=:v and lease_until>transaction_timestamp() for update")
      .param("j",claim.jobId()).param("w",claim.worker()).param("v",claim.stateVersion()).query().listOfRows();
    if(rows.size()!=1)throw new IllegalStateException("UDP_RESOLUTION_LEASE_LOST");
    return rows.getFirst();
  }
  private void stop(ResolutionRepository.Claim claim,String code,int attempts){
    db.sql("update ouf_udp.materialization_job set state='QUARANTINED',state_version=state_version+1,claimed_by=null,lease_until=null,next_integrity_check_at=null,safe_failure_code=:code,updated_at=transaction_timestamp() where job_id=:j")
      .param("code",code).param("j",claim.jobId()).update();
    event(claim,"PUBLICATION_DEPENDENCY_EXHAUSTED",attempts);
  }
  private void event(ResolutionRepository.Claim claim,String type,int attempts){
    db.sql("insert into ouf_udp.handoff_event(event_id,handoff_id,event_type,safe_detail) select gen_random_uuid(),handoff_id,:type,jsonb_build_object('episodeId',dependency_episode_id,'attempts',:attempts,'cumulativeAttempts',dependency_cumulative_attempts,'profile','ouf.publication-read-retry.v1') from ouf_udp.materialization_job where job_id=:j")
      .param("type",type).param("attempts",attempts).param("j",claim.jobId()).update();
  }
  private static Instant time(Map<String,Object> row,String key){Object value=row.get(key);return value instanceof OffsetDateTime offset?offset.toInstant():((java.sql.Timestamp)value).toInstant();}
  static long delayMillis(int attempts,double multiplier){
    if(attempts<1||multiplier<.8||multiplier>1.2)throw new IllegalArgumentException();
    long[] delays={5000,15000,60000,300000,900000};
    return (long)(delays[Math.min(attempts-1,delays.length-1)]*multiplier);
  }
}
