package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class LakeMaintenanceRuntimeTest {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_UDP_DB_URL"));r.add("spring.datasource.username",()->required("OUF_UDP_DB_USER"));r.add("spring.datasource.password",()->required("OUF_UDP_DB_PASSWORD"));r.add("ouf.udp.lake.maintenance.initial-delay-ms",()->3600000);r.add("ouf.udp.lake.maintenance.worker-id",()->"scheduled-test-worker");}
  @Autowired LakeMaintenanceRepository schedules;@Autowired LakeMaintenanceWorker worker;@Autowired MaintenanceStorage storage;@Autowired JdbcClient db;

  @BeforeEach void reset(){storage.fail=false;db.sql("truncate table ouf_udp.lake_maintenance_attempt").update();db.sql("update ouf_udp.lake_maintenance_schedule set state='READY',next_run_at=transaction_timestamp()-interval '1 second',lease_owner=null,lease_until=null,lease_generation=0,consecutive_failures=0,last_safe_error_code=null,last_scan_id=null,last_started_at=null,last_completed_at=null").update();}

  @Test void concurrentWorkersClaimTheDueTaskOnlyOnce()throws Exception{try(var pool=Executors.newFixedThreadPool(2)){var start=new CountDownLatch(1);Callable<Optional<LakeMaintenanceRepository.Claim>> claim=()->{start.await();return schedules.claim(Thread.currentThread().getName(),Duration.ofMinutes(5));};var a=pool.submit(claim);var b=pool.submit(claim);start.countDown();var claims=List.of(a.get(),b.get());assertThat(claims.stream().filter(Optional::isPresent).count()).isOne();var winner=claims.stream().flatMap(Optional::stream).findFirst().orElseThrow();schedules.fail(winner,"UDP_TEST_CLEANUP");}}

  @Test void successfulScanIsDurablyRecordedAndRescheduled(){assertThat(worker.runOnce()).isTrue();Map<String,Object> schedule=db.sql("select state,consecutive_failures,last_scan_id,last_completed_at from ouf_udp.lake_maintenance_schedule where task_name='STORAGE_RECONCILIATION'").query().singleRow();assertThat(schedule.get("state")).isEqualTo("READY");assertThat(schedule.get("consecutive_failures")).isEqualTo(0);assertThat(schedule.get("last_scan_id")).isNotNull();assertThat(schedule.get("last_completed_at")).isNotNull();assertThat(db.sql("select state from ouf_udp.lake_maintenance_attempt").query(String.class).single()).isEqualTo("SUCCEEDED");}

  @Test void failureUsesSafeCodeAndBackoffWithoutLosingHistory(){storage.fail=true;assertThat(worker.runOnce()).isTrue();Map<String,Object> schedule=db.sql("select state,consecutive_failures,last_safe_error_code,(next_run_at>transaction_timestamp()) future from ouf_udp.lake_maintenance_schedule where task_name='STORAGE_RECONCILIATION'").query().singleRow();assertThat(schedule).containsEntry("state","READY").containsEntry("consecutive_failures",1).containsEntry("last_safe_error_code","UDP_LAKE_MAINTENANCE_FAILED").containsEntry("future",true);assertThat(db.sql("select state,safe_error_code from ouf_udp.lake_maintenance_attempt").query().singleRow()).containsEntry("state","FAILED").containsEntry("safe_error_code","UDP_LAKE_MAINTENANCE_FAILED");assertThatThrownBy(()->db.sql("delete from ouf_udp.lake_maintenance_attempt").update()).hasMessageContaining("append-only");}

  @Test void expiredLeaseIsRecoveredWithImmutableEvidence(){var first=schedules.claim("dead-worker",Duration.ofMinutes(5)).orElseThrow();db.sql("update ouf_udp.lake_maintenance_schedule set lease_until=transaction_timestamp()-interval '1 second' where task_name=:task").param("task",first.task()).update();var recovered=schedules.claim("recovery-worker",Duration.ofMinutes(5)).orElseThrow();assertThat(recovered.generation()).isEqualTo(first.generation()+1);assertThat(db.sql("select state,safe_error_code from ouf_udp.lake_maintenance_attempt").query().singleRow()).containsEntry("state","LEASE_LOST").containsEntry("safe_error_code","UDP_LAKE_MAINTENANCE_LEASE_EXPIRED");schedules.fail(recovered,"UDP_TEST_CLEANUP");}

  @TestConfiguration static class Config {@Bean MaintenanceStorage storage(){return new MaintenanceStorage();}}
  static class MaintenanceStorage implements LakeObjectStoragePort {boolean fail;public StoredObject put(String k,byte[] c,String m,String h){return new StoredObject(k,h,c.length,true);}public Optional<StoredObject> head(String l){return Optional.empty();}public byte[] read(String l){return new byte[0];}public Collection<StoredObject> inventory(){if(fail)throw new IllegalStateException("provider detail must not persist");return List.of();}public void delete(String l){}}
  private static String required(String name){String value=System.getenv(name);if(value==null)throw new IllegalStateException(name+" required");return value;}
}
