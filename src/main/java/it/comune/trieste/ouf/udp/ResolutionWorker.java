package it.comune.trieste.ouf.udp;

import java.time.Duration;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

@Service @ConditionalOnBean(UdpPorts.ResolutionConfigurationPort.class)
public class ResolutionWorker {
  private final ResolutionRepository jobs;private final ObjectResolutionService resolution;private final UdpPorts.ResolutionConfigurationPort configuration;
  public ResolutionWorker(ResolutionRepository jobs,ObjectResolutionService resolution,UdpPorts.ResolutionConfigurationPort configuration){this.jobs=jobs;this.resolution=resolution;this.configuration=configuration;}
  public Optional<String> executeOne(String worker){var claimed=jobs.claim(worker,Duration.ofMinutes(2));if(claimed.isEmpty())return Optional.empty();var c=claimed.orElseThrow();try{Map<String,Object> refs=HandoffIntakeService.object(c.payload(),"contractRefs");var profile=configuration.resolve(HandoffIntakeService.text(refs,"bundleRef"),c.typeCode());resolution.resolve(c.handoffId(),c.payload(),profile);jobs.complete(c);}catch(RuntimeException e){jobs.fail(c,e.getMessage());}return Optional.of(c.handoffId());}
}
