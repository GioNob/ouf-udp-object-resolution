package it.comune.trieste.ouf.udp;

import java.time.Duration;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

@Service @ConditionalOnBean({UdpPorts.ResolutionConfigurationPort.class,UdpPorts.MaterializationConfigurationPort.class})
public class ResolutionWorker {
  @org.springframework.beans.factory.annotation.Autowired private org.springframework.transaction.support.TransactionTemplate transactions;
  private final ResolutionRepository jobs;private final MaterializationReferenceGate referenceGate;private final ObjectResolutionService resolution;private final CanonicalMaterializer materializer;private final UdpPorts.ResolutionConfigurationPort resolutionConfig;private final UdpPorts.MaterializationConfigurationPort materializationConfig;
  public ResolutionWorker(ResolutionRepository jobs,MaterializationReferenceGate referenceGate,ObjectResolutionService resolution,CanonicalMaterializer materializer,UdpPorts.ResolutionConfigurationPort resolutionConfig,UdpPorts.MaterializationConfigurationPort materializationConfig){this.jobs=jobs;this.referenceGate=referenceGate;this.resolution=resolution;this.materializer=materializer;this.resolutionConfig=resolutionConfig;this.materializationConfig=materializationConfig;}
  public Optional<String> executeOne(String worker){
    var claimed=jobs.claim(worker,Duration.ofMinutes(2));
    if(claimed.isEmpty())return Optional.empty();
    var claim=claimed.orElseThrow();
    try{
      if(!referenceGate.verify(claim))return Optional.of(claim.handoffId());
      var refs=HandoffIntakeService.object(claim.payload(),"contractRefs");
      String bundle=HandoffIntakeService.text(refs,"bundleRef");
      var resolutionProfile=resolutionConfig.resolve(bundle,claim.typeCode());
      var materializationProfile=materializationConfig.resolve(bundle,claim.typeCode());
      referenceGate.profilesReady(claim);
      materializer.validatePayload(claim.payload(),materializationProfile);
      jobs.recordMaterializationType(claim,resolutionProfile.canonicalType());
      transactions.executeWithoutResult(status->{
        jobs.lockClaim(claim);
        materializer.rememberProfile(claim.handoffId(),resolutionProfile.canonicalType(),bundle,materializationProfile);
        var decision=resolution.resolve(claim.handoffId(),claim.payload(),resolutionProfile);
        if("REVIEW_REQUIRED".equals(decision.outcome())){
          jobs.quarantine(claim,"UDP_RESOLUTION_REVIEW_REQUIRED");
          return;
        }
        if(Set.of("MATCH","NEW_OBJECT").contains(decision.outcome()))
          materializer.materialize(claim.handoffId(),decision.targetUrbanObjectId(),claim.payload(),materializationProfile);
        jobs.complete(claim);
      });
    }catch(PublicationAccessFailure failure){referenceGate.transportFailure(claim,failure);}
    catch(RuntimeException failure){jobs.fail(claim,failure.getMessage());}
    return Optional.of(claim.handoffId());
  }
}
