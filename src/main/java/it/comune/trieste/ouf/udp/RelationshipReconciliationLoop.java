package it.comune.trieste.ouf.udp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="ouf.udp.execution.enabled",havingValue="true")
public class RelationshipReconciliationLoop {
  private final RelationshipReconciliation reconciliation;
  public RelationshipReconciliationLoop(RelationshipReconciliation reconciliation){this.reconciliation=reconciliation;}
  @Scheduled(fixedDelayString="${ouf.udp.relationships.poll-delay-ms:5000}")
  public void tick(){reconciliation.tick();}
}
