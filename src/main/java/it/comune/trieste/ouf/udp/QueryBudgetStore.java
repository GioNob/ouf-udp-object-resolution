package it.comune.trieste.ouf.udp;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class QueryBudgetStore {
  private final JdbcClient db;
  private final TransactionTemplate transaction;
  private final Semaphore bulkhead;
  private final long acquireTimeoutMs;

  public QueryBudgetStore(JdbcClient db, PlatformTransactionManager transactions,
      @Value("${ouf.udp.query-budget.bulkhead-permits:2}") int permits,
      @Value("${ouf.udp.query-budget.bulkhead-acquire-timeout-ms:100}") long acquireTimeoutMs) {
    if (permits < 1 || acquireTimeoutMs < 1)
      throw new IllegalArgumentException("UDP_QUERY_BUDGET_BULKHEAD_CONFIG_INVALID");
    this.db = db;
    this.transaction = new TransactionTemplate(transactions);
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.transaction.setTimeout(1);
    this.bulkhead = new Semaphore(permits, true);
    this.acquireTimeoutMs = acquireTimeoutMs;
  }

  public Snapshot consume(ServingAuthorizationContext auth,String workload,int calls,long nodes,long edges,long bytes,long millis) {
    return guarded(() -> transaction.execute(status ->
        consumeTransaction(auth, workload, calls, nodes, edges, bytes, millis)));
  }

  public void mismatch(ServingAuthorizationContext auth,String workload) {
    guarded(() -> {
      transaction.executeWithoutResult(status -> mismatchTransaction(auth, workload));
      return null;
    });
  }

  private Snapshot consumeTransaction(ServingAuthorizationContext auth,String workload,int calls,long nodes,long edges,long bytes,long millis) {
    OffsetDateTime expires=OffsetDateTime.now().plusMinutes(15);
    db.sql("delete from ouf_udp.query_budget where correlation_id=:c and principal_subject=:p and tenant_id=:t and workload_class=:w and expires_at<=transaction_timestamp()").param("c",auth.correlationId()).param("p",auth.subject()).param("t",auth.tenantId()).param("w",workload).update();
    List<Map<String,Object>> rows=db.sql("insert into ouf_udp.query_budget(correlation_id,principal_subject,tenant_id,workload_class,expires_at,graph_calls,query_calls,nodes_observed,edges_observed,result_bytes,db_time_ms) values(:c,:p,:t,:w,:x,:g,1,:n,:e,:b,:m) on conflict(correlation_id,principal_subject,tenant_id,workload_class) do update set graph_calls=query_budget.graph_calls+excluded.graph_calls,query_calls=query_budget.query_calls+1,nodes_observed=query_budget.nodes_observed+excluded.nodes_observed,edges_observed=query_budget.edges_observed+excluded.edges_observed,result_bytes=query_budget.result_bytes+excluded.result_bytes,db_time_ms=query_budget.db_time_ms+excluded.db_time_ms,lock_version=query_budget.lock_version+1 where query_budget.graph_calls+excluded.graph_calls<=20 and query_budget.query_calls+1<=30 and query_budget.nodes_observed+excluded.nodes_observed<=2000 and query_budget.edges_observed+excluded.edges_observed<=4000 and query_budget.result_bytes+excluded.result_bytes<=2097152 and query_budget.db_time_ms+excluded.db_time_ms<=20000 returning graph_calls,query_calls,mismatch_calls,nodes_observed,edges_observed,result_bytes,db_time_ms,expires_at").param("c",auth.correlationId()).param("p",auth.subject()).param("t",auth.tenantId()).param("w",workload).param("x",expires,Types.TIMESTAMP_WITH_TIMEZONE).param("g",calls).param("n",nodes).param("e",edges).param("b",bytes).param("m",millis).query().listOfRows();
    if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"UDP_QUERY_BUDGET_EXHAUSTED");
    Map<String,Object> r=rows.getFirst();
    return new Snapshot(number(r,"graph_calls"),number(r,"query_calls"),number(r,"mismatch_calls"),number(r,"nodes_observed"),number(r,"edges_observed"),number(r,"result_bytes"),number(r,"db_time_ms"),offset(r.get("expires_at")));
  }

  private void mismatchTransaction(ServingAuthorizationContext auth,String workload) {
    db.sql("delete from ouf_udp.query_budget where correlation_id=:c and principal_subject=:p and tenant_id=:t and workload_class=:w and expires_at<=transaction_timestamp()").param("c",auth.correlationId()).param("p",auth.subject()).param("t",auth.tenantId()).param("w",workload).update();
    db.sql("insert into ouf_udp.query_budget(correlation_id,principal_subject,tenant_id,workload_class,expires_at,mismatch_calls) values(:c,:p,:t,:w,:x,1) on conflict(correlation_id,principal_subject,tenant_id,workload_class) do update set mismatch_calls=query_budget.mismatch_calls+1,lock_version=query_budget.lock_version+1 where query_budget.mismatch_calls<20").param("c",auth.correlationId()).param("p",auth.subject()).param("t",auth.tenantId()).param("w",workload).param("x",OffsetDateTime.now().plusMinutes(15),Types.TIMESTAMP_WITH_TIMEZONE).update();
  }

  private <T> T guarded(Operation<T> operation) {
    boolean acquired;
    try {
      acquired = bulkhead.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "UDP_QUERY_BUDGET_BULKHEAD_INTERRUPTED", interrupted);
    }
    if (!acquired)
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "UDP_QUERY_BUDGET_BULKHEAD_SATURATED");
    try {
      return operation.run();
    } finally {
      bulkhead.release();
    }
  }

  int availablePermits() { return bulkhead.availablePermits(); }

  @FunctionalInterface
  private interface Operation<T> { T run(); }

  private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
  private static OffsetDateTime offset(Object value){if(value instanceof OffsetDateTime o)return o;if(value instanceof java.sql.Timestamp t)return t.toInstant().atOffset(ZoneOffset.UTC);throw new IllegalStateException("Unsupported timestamp type: "+value.getClass().getName());}
  public record Snapshot(long graphCalls,long queryCalls,long mismatchCalls,long nodesObserved,long edgesObserved,long resultBytes,long dbTimeMs,OffsetDateTime expiresAt){}
}
