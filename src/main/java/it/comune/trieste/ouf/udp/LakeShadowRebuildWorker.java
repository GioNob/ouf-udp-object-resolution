package it.comune.trieste.ouf.udp;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LakeShadowRebuildWorker {
  private final LakeShadowRebuildService service;private final String worker;private final long lease;private final MeterRegistry metrics;
  public LakeShadowRebuildWorker(LakeShadowRebuildService service,MeterRegistry metrics,@Value("${ouf.udp.lake.shadow.worker-id:}") String worker,@Value("${ouf.udp.lake.shadow.lease-seconds:1800}") long lease){this.service=service;this.metrics=metrics;this.worker=worker.isBlank()?"lake-shadow-"+UUID.randomUUID():worker;this.lease=lease;}
  @Scheduled(fixedDelayString="${ouf.udp.lake.shadow.poll-ms:60000}",initialDelayString="${ouf.udp.lake.shadow.initial-delay-ms:300000}") public void poll(){runOnce();}
  public boolean runOnce(){var claim=service.claim(worker,lease);if(claim.isEmpty())return false;try{service.build(claim.get());metrics.counter("ouf.udp.lake.shadow.completed","outcome","success").increment();}catch(RuntimeException failure){metrics.counter("ouf.udp.lake.shadow.completed","outcome","failure").increment();}return true;}
}
