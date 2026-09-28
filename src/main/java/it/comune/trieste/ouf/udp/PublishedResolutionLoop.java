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
  private final GovernedIdentityResolutionService governed;
  private final GovernedIdentityIndexBackfill identityIndex;
  private final String worker="published-resolution-"+UUID.randomUUID();
  @org.springframework.beans.factory.annotation.Autowired
  public PublishedResolutionLoop(ResolutionRepository jobs,PublishedRuntimeConfiguration configuration,MaterializationReferenceGate gate,ObjectResolutionService resolution,CanonicalMaterializer materializer,SpatialMaterializer spatial,JdbcClient db,TransactionTemplate tx,RelationshipReconciliation relationships,GovernedIdentityResolutionService governed,GovernedIdentityIndexBackfill identityIndex){this.relationships=relationships;this.governed=governed;this.identityIndex=identityIndex;this.jobs=jobs;this.configuration=configuration;this.gate=gate;this.resolution=resolution;this.materializer=materializer;this.spatial=spatial;this.db=db;this.tx=tx;}
  public PublishedResolutionLoop(ResolutionRepository jobs,PublishedRuntimeConfiguration configuration,MaterializationReferenceGate gate,ObjectResolutionService resolution,CanonicalMaterializer materializer,SpatialMaterializer spatial,JdbcClient db,TransactionTemplate tx,RelationshipReconciliation relationships){this(jobs,configuration,gate,resolution,materializer,spatial,db,tx,relationships,null,null);}
  public PublishedResolutionLoop(ResolutionRepository jobs,PublishedRuntimeConfiguration configuration,MaterializationReferenceGate gate,ObjectResolutionService resolution,CanonicalMaterializer materializer,SpatialMaterializer spatial,JdbcClient db,TransactionTemplate tx){this(jobs,configuration,gate,resolution,materializer,spatial,db,tx,null,null,null);}
  @Scheduled(fixedDelayString="${ouf.udp.execution.poll-delay-ms:1000}",initialDelayString="${ouf.udp.execution.initial-delay-ms:1000}")
  public void tick(){
    var claimed=jobs.claim(worker,Duration.ofMinutes(2));if(claimed.isEmpty())return;var claim=claimed.orElseThrow();
    try{
      if(!gate.verify(claim))return;
      var refs=HandoffIntakeService.object(claim.payload(),"contractRefs");
      var profiles=configuration.resolve(HandoffIntakeService.text(refs,"bundleRef"),claim.typeCode());
      tx.executeWithoutResult(status->{
        if(db.sql("select 1 from ouf_udp.materialization_job where job_id=:j and state='RUNNING' and claimed_by=:w and lease_until>transaction_timestamp() for update").param("j",claim.jobId()).param("w",worker).query(Integer.class).optional().isEmpty())throw new IllegalStateException("UDP_RESOLUTION_LEASE_LOST");
        db.sql("select pg_advisory_xact_lock(hashtextextended(:key,0))").param("key","resolution:"+profiles.canonicalType()).query().singleRow();
        GovernedCrsTransform.Result geometry=null;
        if(profiles.spatial()!=null){geometry=spatial.prepare(claim.handoffId(),claim.payload(),profiles.spatial());if(!"OK".equals(geometry.status())){jobs.quarantine(claim,"UDP_SPATIAL_REVIEW_REQUIRED");return;}}
        var identity=profiles.identity();
        var governedDecision=identity==null?null:governed.resolve(claim.handoffId(),claim.payload(),profiles.materialization(),identity);
        var legacyDecision=identity==null?resolution.resolve(claim.handoffId(),claim.payload(),profiles.resolution()):null;
        String outcome=identity==null?legacyDecision.outcome():governedDecision.outcome();
        if("REVIEW_REQUIRED".equals(outcome)){jobs.quarantine(claim,"UDP_RESOLUTION_REVIEW_REQUIRED");return;}
        if(Set.of("MATCH","NEW_OBJECT").contains(outcome)&&!(identity!=null&&governedDecision.duplicate())){
          if(identity!=null&&governedDecision.coverageRef()==null)
            throw new IllegalStateException("UDP_IDENTITY_COVERAGE_UNVERIFIED");
          UUID target=identity==null?legacyDecision.targetUrbanObjectId():governedDecision.targetUrbanObjectId();
          if(profiles.spatial()!=null)spatial.materializePrepared(claim.handoffId(),target,claim.payload(),profiles.spatial(),geometry);
          materializer.materialize(claim.handoffId(),target,claim.payload(),profiles.materialization());
          if(identity!=null)identityIndex.refreshOne(identity,target,governedDecision.coverageRef());
          if(relationships!=null)relationships.accept(claim.handoffId(),target,claim.payload(),profiles.relationships());
        }
        jobs.complete(claim);
      });
    }catch(RuntimeException failure){if(!"UDP_RESOLUTION_LEASE_LOST".equals(failure.getMessage()))jobs.fail(claim,failure.getMessage());LOG.warn("UDP_AUTOMATIC_RESOLUTION_INCOMPLETE");}
  }
}
