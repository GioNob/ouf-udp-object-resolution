package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
class QueryBudgetResilienceRuntimeTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> required("OUF_UDP_DB_URL"));
    r.add("spring.datasource.username", () -> required("OUF_UDP_DB_USER"));
    r.add("spring.datasource.password", () -> required("OUF_UDP_DB_PASSWORD"));
  }

  @Autowired JdbcClient db;

  @BeforeEach void clean() {
    db.sql("truncate table ouf_udp.capability_retry_guard,ouf_udp.query_budget").update();
  }

  @Test
  void contentionBeyondBulkheadFailsBeforeHikariPoolExhaustion() throws Exception {
    try (Replica replica = replica("bulkhead", 4, 2, 100);
         ExecutorService workers = Executors.newFixedThreadPool(2)) {
      ServingAuthorizationContext auth = context("corr-bulkhead");
      replica.budgets.consume(auth, "GRAPH", 0, 1, 1, 1, 1);

      try (Connection blocker = replica.dataSource.getConnection()) {
        blocker.setAutoCommit(false);
        try (var statement = blocker.prepareStatement(
            "select 1 from ouf_udp.query_budget where correlation_id=? and principal_subject=? and tenant_id=? and workload_class=? for update")) {
          statement.setString(1, auth.correlationId());
          statement.setString(2, auth.subject());
          statement.setString(3, auth.tenantId());
          statement.setString(4, "GRAPH");
          statement.executeQuery();
        }

        Future<?> first = workers.submit(() ->
            replica.budgets.consume(auth, "GRAPH", 0, 1, 1, 1, 1));
        Future<?> second = workers.submit(() ->
            replica.budgets.consume(auth, "GRAPH", 0, 1, 1, 1, 1));
        awaitNoPermits(replica.budgets);

        long started = System.nanoTime();
        assertThatThrownBy(() -> replica.budgets.consume(auth, "GRAPH", 0, 1, 1, 1, 1))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("UDP_QUERY_BUDGET_BULKHEAD_SATURATED");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(500);
        assertThat(replica.dataSource.getHikariPoolMXBean().getActiveConnections())
            .isLessThan(replica.dataSource.getMaximumPoolSize());

        blocker.rollback();
        first.get(2, TimeUnit.SECONDS);
        second.get(2, TimeUnit.SECONDS);
      }
      assertThat(replica.budgets.availablePermits()).isEqualTo(2);
    }
  }

  @Test
  void independentReplicasShareAdversarialRetryStateAndSurviveRestart() throws Exception {
    ServingAuthorizationContext auth = context("corr-adversarial");
    UUID anchor = UUID.randomUUID();
    AtomicInteger mismatch = new AtomicInteger();
    AtomicInteger stalled = new AtomicInteger();

    try (Replica first = replica("replica-a", 3, 3, 500);
         Replica second = replica("replica-b", 3, 3, 500);
         ExecutorService callers = Executors.newFixedThreadPool(6)) {
      List<Callable<Void>> calls = new ArrayList<>();
      for (int index = 0; index < 20; index++) {
        Replica target = index % 2 == 0 ? first : second;
        calls.add(() -> {
          try {
            target.router.authorizeTraverse(new CapabilityRouter.TraversalIntent(
                anchor, List.of("ouf:locatedOn"), CapabilityRouter.TraversalPurpose.CROSS_DOMAIN_RELATIONSHIP), auth);
          } catch (CapabilityMismatchException expected) {
            mismatch.incrementAndGet();
          } catch (ToolSelectionStalledException expected) {
            stalled.incrementAndGet();
          }
          return null;
        });
      }
      for (Future<Void> result : callers.invokeAll(calls)) result.get();
    }

    assertThat(mismatch).hasValue(19);
    assertThat(stalled).hasValue(1);
    Map<String,Object> retry = db.sql(
        "select attempt_count,state from ouf_udp.capability_retry_guard where correlation_id='corr-adversarial'")
        .query().singleRow();
    assertThat(retry.get("attempt_count")).isEqualTo(20);
    assertThat(retry.get("state")).isEqualTo("STALLED");
    assertThat(db.sql(
        "select mismatch_calls from ouf_udp.query_budget where correlation_id='corr-adversarial'")
        .query(Integer.class).single()).isEqualTo(19);

    try (Replica restarted = replica("replica-a-restarted", 2, 2, 200)) {
      restarted.budgets.consume(auth, "GRAPH", 0, 1, 0, 0, 1);
    }
    Map<String,Object> persisted = db.sql(
        "select mismatch_calls,query_calls,nodes_observed from ouf_udp.query_budget where correlation_id='corr-adversarial'")
        .query().singleRow();
    assertThat(persisted.get("mismatch_calls")).isEqualTo(19);
    assertThat(persisted.get("query_calls")).isEqualTo(1);
    assertThat(((Number) persisted.get("nodes_observed")).longValue()).isEqualTo(1);
  }

  private static void awaitNoPermits(QueryBudgetStore store) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (store.availablePermits() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
    assertThat(store.availablePermits()).isZero();
  }

  private static Replica replica(String name, int poolSize, int permits, long timeoutMs) {
    HikariConfig config = new HikariConfig();
    config.setPoolName(name);
    config.setJdbcUrl(required("OUF_UDP_DB_URL"));
    config.setUsername(required("OUF_UDP_DB_USER"));
    config.setPassword(required("OUF_UDP_DB_PASSWORD"));
    config.setMaximumPoolSize(poolSize);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(500);
    HikariDataSource dataSource = new HikariDataSource(config);
    JdbcClient jdbc = JdbcClient.create(new JdbcTemplate(dataSource));
    DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
    QueryBudgetStore budgets = new QueryBudgetStore(jdbc, transactions, permits, timeoutMs);
    CapabilityRetryGuard retry = new CapabilityRetryGuard(jdbc);
    CapabilityRouter router = new CapabilityRouter(budgets, retry, 20);
    return new Replica(dataSource, budgets, router);
  }

  private static ServingAuthorizationContext context(String correlation) {
    return new ServingAuthorizationContext("AI_AGENT", "agent", "default",
        Set.of("urban.graph.traverse"), Set.of("OPEN"), "authz://resilience", correlation);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null) throw new IllegalStateException(name + " required");
    return value;
  }

  private record Replica(HikariDataSource dataSource, QueryBudgetStore budgets,
                         CapabilityRouter router) implements AutoCloseable {
    @Override public void close() { dataSource.close(); }
  }
}
