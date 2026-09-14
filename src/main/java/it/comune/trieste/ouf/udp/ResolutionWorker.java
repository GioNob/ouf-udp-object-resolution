package it.comune.trieste.ouf.udp;

import java.time.Duration;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

@Service @ConditionalOnBean({UdpPorts.ResolutionConfigurationPort.class,UdpPorts.MaterializationConfigurationPort.class})
public class ResolutionWorker {
  private final ResolutionRepository jobs;private final MaterializationReferenceGate referenceGate;private final ObjectResolutionService resolution;private final CanonicalMaterializer materializer;private final UdpPorts.ResolutionConfigurationPort resolutionConfig;private final UdpPorts.MaterializationConfigurationPort materializationConfig;
  public ResolutionWorker(ResolutionRepository jobs,MaterializationReferenceGate referenceGate,ObjectResolutionService resolution,CanonicalMaterializer materializer,UdpPorts.ResolutionConfigurationPort resolutionConfig,UdpPorts.MaterializationConfigurationPort materializationConfig){this.jobs=jobs;this.referenceGate=referenceGate;this.resolution=resolution;this.materializer=materializer;this.resolutionConfig=resolutionConfig;this.materializationConfig=materializationConfig;}
  public Optional<String> executeOne(String worker){var claimed=jobs.claim(worker,Duration.ofMinutes(2));if(claimed.isEmpty())return Optional.empty();var c=claimed.orElseThrow();if(!referenceGate.verify(c))return Optional.of(c.handoffId());try{Map<String,Object> refs=HandoffIntakeService.object(c.payload(),"contractRefs");String bundle=HandoffIntakeService.text(refs,"bundleRef");var decision=resolution.resolve(c.handoffId(),c.payload(),resolutionConfig.resolve(bundle,c.typeCode()));if(Set.of("MATCH","NEW_OBJECT").contains(decision.outcome()))materializer.materialize(c.handoffId(),decision.targetUrbanObjectId(),c.payload(),materializationConfig.resolve(bundle,c.typeCode()));jobs.complete(c);}catch(RuntimeException e){jobs.fail(c,e.getMessage());}return Optional.of(c.handoffId());}
}
