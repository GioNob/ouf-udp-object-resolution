package it.comune.trieste.ouf.udp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LakeMaintenanceWorker {
  private final LakeMaintenanceRepository schedules;private final LakeLifecycleService lake;private final String worker;private final Duration lease;private final Counter success;private final Counter failure;
  public LakeMaintenanceWorker(LakeMaintenanceRepository schedules,LakeLifecycleService lake,MeterRegistry metrics,@Value("${ouf.udp.lake.maintenance.worker-id:}") String configuredWorker,@Value("${ouf.udp.lake.maintenance.lease-seconds:900}") long leaseSeconds){this.schedules=schedules;this.lake=lake;this.worker=configuredWorker.isBlank()?"lake-maintenance-"+UUID.randomUUID():configuredWorker;this.lease=Duration.ofSeconds(Math.max(60,leaseSeconds));this.success=metrics.counter("ouf.udp.lake.maintenance.completed","outcome","success");this.failure=metrics.counter("ouf.udp.lake.maintenance.completed","outcome","failure");}

  @Scheduled(fixedDelayString="${ouf.udp.lake.maintenance.poll-ms:60000}",initialDelayString="${ouf.udp.lake.maintenance.initial-delay-ms:300000}")
  public void poll(){runOnce();}
  public boolean runOnce(){var claimed=schedules.claim(worker,lease);if(claimed.isEmpty())return false;var claim=claimed.get();try{var scan=lake.reconcile();schedules.succeed(claim,scan);success.increment();}catch(RuntimeException problem){schedules.fail(claim,safeCode(problem));failure.increment();}return true;}
  private static String safeCode(RuntimeException failure){String message=failure.getMessage();return message!=null&&message.matches("UDP_[A-Z0-9_]+")?message:"UDP_LAKE_MAINTENANCE_FAILED";}
}
