package it.comune.trieste.ouf.udp;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CapabilityRetryGuard {
  private final JdbcClient db;

  public CapabilityRetryGuard(JdbcClient db) { this.db = db; }

  @Transactional(timeout = 1, propagation = Propagation.REQUIRES_NEW)
  public Snapshot register(ServingAuthorizationContext auth, String requestedCapability,
                           String semanticFingerprint, int threshold) {
    if (threshold < 1) throw new IllegalArgumentException("UDP_RETRY_THRESHOLD_INVALID");
    db.sql("delete from ouf_udp.capability_retry_guard where correlation_id=:c and principal_subject=:p and tenant_id=:t and requested_capability=:cap and semantic_fingerprint=:f and expires_at<=transaction_timestamp()")
        .param("c", auth.correlationId()).param("p", auth.subject()).param("t", auth.tenantId())
        .param("cap", requestedCapability).param("f", semanticFingerprint).update();
    Map<String,Object> row = db.sql("insert into ouf_udp.capability_retry_guard(correlation_id,principal_subject,tenant_id,requested_capability,semantic_fingerprint,attempt_count,threshold,state,expires_at) values(:c,:p,:t,:cap,:f,1,:n,case when :n=1 then 'STALLED' else 'ACTIVE' end,:x) on conflict(correlation_id,principal_subject,tenant_id,requested_capability,semantic_fingerprint) do update set attempt_count=case when capability_retry_guard.state='STALLED' then capability_retry_guard.attempt_count else least(capability_retry_guard.attempt_count+1,excluded.threshold) end,threshold=excluded.threshold,state=case when capability_retry_guard.state='STALLED' or capability_retry_guard.attempt_count+1>=excluded.threshold then 'STALLED' else 'ACTIVE' end,updated_at=transaction_timestamp(),expires_at=excluded.expires_at returning attempt_count,threshold,state")
        .param("c", auth.correlationId()).param("p", auth.subject()).param("t", auth.tenantId())
        .param("cap", requestedCapability).param("f", semanticFingerprint).param("n", threshold)
        .param("x", OffsetDateTime.now().plusMinutes(15), Types.TIMESTAMP_WITH_TIMEZONE)
        .query().singleRow();
    return new Snapshot(((Number)row.get("attempt_count")).intValue(),
        ((Number)row.get("threshold")).intValue(), "STALLED".equals(row.get("state")));
  }

  public record Snapshot(int attempts, int threshold, boolean stalled) {}
}
