package it.comune.trieste.ouf.udp;

import java.time.Duration;
import java.util.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component @ConditionalOnProperty(name="ouf.udp.execution.enabled",havingValue="true")
public class PublishedResolutionLoop {
  private static final Logger LOG=LoggerFactory.getLogger(PublishedResolutionLoop.class);
  private final ResolutionRepository jobs;private final PublishedRuntimeConfiguration configuration;private final MaterializationReferenceGate gate;private final ObjectResolutionService resolution;private final CanonicalMaterializer materializer;private final SpatialMaterializer spatial;private final JdbcClient db;private final TransactionTemplate tx;
  private final RelationshipReconciliation relationships;
  private final String worker="published-resolution-"+UUID.randomUUID();
  @org.springframework.beans.factory.annotation.Autowired
  public PublishedResolutionLoop(ResolutionRepository jobs,PublishedRuntimeConfiguration configuration,MaterializationReferenceGate gate,ObjectResolutionService resolution,CanonicalMaterializer materializer,SpatialMaterializer spatial,JdbcClient db,TransactionTemplate tx,RelationshipReconciliation relationships){this.relationships=relationships;this.jobs=jobs;this.configuration=configuration;this.gate=gate;this.resolution=resolution;this.materializer=materializer;this.spatial=spatial;this.db=db;this.tx=tx;}
  public PublishedResolutionLoop(ResolutionRepository jobs,PublishedRuntimeConfiguration configuration,MaterializationReferenceGate gate,ObjectResolutionService resolution,CanonicalMaterializer materializer,SpatialMaterializer spatial,JdbcClient db,TransactionTemplate tx){this(jobs,configuration,gate,resolution,materializer,spatial,db,tx,null);}
  @Scheduled(fixedDelayString="${ouf.udp.execution.poll-delay-ms:1000}",initialDelayString="${ouf.udp.execution.initial-delay-ms:1000}")
  public void tick(){
    var claimed=jobs.claim(worker,Duration.ofMinutes(2));if(claimed.isEmpty())return;var claim=claimed.orElseThrow();
    try{
      if(!gate.verify(claim))return;
      var refs=HandoffIntakeService.object(claim.payload(),"contractRefs");
      var profiles=configuration.resolve(HandoffIntakeService.text(refs,"bundleRef"),claim.typeCode());
      tx.executeWithoutResult(status->{
        if(db.sql("select 1 from ouf_udp.materialization_job where job_id=:j and state='RUNNING' and claimed_by=:w and lease_until>transaction_timestamp() for update").param("j",claim.jobId()).param("w",worker).query(Integer.class).optional().isEmpty())throw new IllegalStateException("UDP_RESOLUTION_LEASE_LOST");
        db.sql("select pg_advisory_xact_lock(hashtextextended(:key,0))").param("key","resolution:"+profiles.resolution().canonicalType()).query().singleRow();
        GovernedCrsTransform.Result geometry=null;
        if(profiles.spatial()!=null){geometry=spatial.prepare(claim.handoffId(),claim.payload(),profiles.spatial());if(!"OK".equals(geometry.status())){jobs.quarantine(claim,"UDP_SPATIAL_REVIEW_REQUIRED");return;}}
        var decision=resolution.resolve(claim.handoffId(),claim.payload(),profiles.resolution(),geometry);
        if(Set.of("MATCH","NEW_OBJECT").contains(decision.outcome())){
          if(profiles.spatial()!=null){
            String action=spatial.currentAction(claim.handoffId(),decision.targetUrbanObjectId(),claim.payload(),profiles.spatial(),profiles.materialization(),geometry);
            if("REVIEW_REQUIRED".equals(action)){jobs.quarantine(claim,"SPATIAL_AUTHORITY_CONFLICT");return;}
            if("ADVANCE".equals(action))spatial.materializePrepared(claim.handoffId(),decision.targetUrbanObjectId(),claim.payload(),profiles.spatial(),geometry);
          }
          materializer.materialize(claim.handoffId(),decision.targetUrbanObjectId(),claim.payload(),profiles.materialization());
          if(relationships!=null)relationships.accept(claim.handoffId(),decision.targetUrbanObjectId(),claim.payload(),profiles.relationships());
        }
        jobs.complete(claim);
      });
    }catch(RuntimeException failure){if(!"UDP_RESOLUTION_LEASE_LOST".equals(failure.getMessage()))jobs.fail(claim,failure.getMessage());LOG.warn("UDP_AUTOMATIC_RESOLUTION_INCOMPLETE");}
  }
}
