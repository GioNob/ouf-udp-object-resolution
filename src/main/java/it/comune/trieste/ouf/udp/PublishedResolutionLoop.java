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
  // Installation tenant (no default): API observations are quarantined while it is blank.
  @org.springframework.beans.factory.annotation.Value("${ouf.udp.lake.tenant-id:}") private String tenant="";
  private final String worker="published-resolution-"+UUID.randomUUID();
  @org.springframework.beans.factory.annotation.Autowired
  public PublishedResolutionLoop(ResolutionRepository jobs,PublishedRuntimeConfiguration configuration,MaterializationReferenceGate gate,ObjectResolutionService resolution,CanonicalMaterializer materializer,SpatialMaterializer spatial,JdbcClient db,TransactionTemplate tx,RelationshipReconciliation relationships,GovernedIdentityResolutionService governed,GovernedIdentityIndexBackfill identityIndex){this.relationships=relationships;this.governed=governed;this.identityIndex=identityIndex;this.jobs=jobs;this.configuration=configuration;this.gate=gate;this.resolution=resolution;this.materializer=materializer;this.spatial=spatial;this.db=db;this.tx=tx;}
  public PublishedResolutionLoop(ResolutionRepository jobs,PublishedRuntimeConfiguration configuration,MaterializationReferenceGate gate,ObjectResolutionService resolution,CanonicalMaterializer materializer,SpatialMaterializer spatial,JdbcClient db,TransactionTemplate tx,RelationshipReconciliation relationships){this(jobs,configuration,gate,resolution,materializer,spatial,db,tx,relationships,null,null);}
  public PublishedResolutionLoop(ResolutionRepository jobs,PublishedRuntimeConfiguration configuration,MaterializationReferenceGate gate,ObjectResolutionService resolution,CanonicalMaterializer materializer,SpatialMaterializer spatial,JdbcClient db,TransactionTemplate tx){this(jobs,configuration,gate,resolution,materializer,spatial,db,tx,null,null,null);}
  @Scheduled(fixedDelayString="${ouf.udp.execution.poll-delay-ms:1000}",initialDelayString="${ouf.udp.execution.initial-delay-ms:1000}")
  public void tick(){
    var claimed=jobs.claimPublished(worker,Duration.ofMinutes(2));if(claimed.isEmpty())return;var claim=claimed.orElseThrow();
    try(var window=GatewayReadPacing.forClaim(claim)){
      if(!gate.verify(claim))return;
      var refs=HandoffIntakeService.object(claim.payload(),"contractRefs");
      boolean api=claim.payload().containsKey("apiObservation");
      String bundle=HandoffIntakeService.text(refs,"bundleRef");
      var profiles=api?configuration.resolveForSource(bundle,claim.typeCode(),
          HandoffIntakeService.text(HandoffIntakeService.object(claim.payload(),"sourceIdentity"),"sourceId"))
          :configuration.resolve(bundle,claim.typeCode());
      gate.profilesReady(claim);
      if(!api)materializer.validatePayload(claim.payload(),profiles.materialization());
      jobs.recordMaterializationType(claim,profiles.canonicalType());
      tx.executeWithoutResult(status->{
        jobs.lockClaim(claim);
        db.sql("select pg_advisory_xact_lock(hashtextextended(:key,0))").param("key","resolution:"+profiles.canonicalType()).query().singleRow();
        var state=api?new ApiSourceObjects(db,tenant,claim):null;
        if(api){
          String block=state.evaluate(profiles.canonicalType());
          if(block!=null){jobs.quarantine(claim,block);return;}
          if(!state.upsert()){
            if(state.outcome==ApiObservation.Outcome.RETIRED && state.binding()!=null){
              state.lockTarget(profiles.canonicalType());
              var coverage=profiles.identity()==null?null:identityIndex.beforeBindingChange(profiles.identity());
              state.withdrawPrevious(state.binding());
              spatial.refreshWithdrawn(state.binding(),claim.handoffId(),profiles.materialization());
              if(relationships!=null)relationships.accept(claim.handoffId(),state.binding(),claim.payload(),null);
              materializer.materialize(claim.handoffId(),state.binding(),claim.payload(),profiles.materialization());
              state.changeBinding(false);
              if(coverage!=null)identityIndex.refreshBindingChange(profiles.identity(),state.binding(),coverage);
            }
            state.finish(state.binding());
            if(state.outcome==ApiObservation.Outcome.CONFLICT)jobs.quarantine(claim,"UDP_EVENT_CONFLICT");
            else jobs.complete(claim);
            return;
          }
          materializer.validatePayload(claim.payload(),profiles.materialization());
          if(state.binding()!=null)state.lockTarget(profiles.canonicalType());
        }
        materializer.rememberProfile(claim.handoffId(),profiles.canonicalType(),HandoffIntakeService.text(refs,"bundleRef"),profiles.materialization());
        GovernedCrsTransform.Result geometry=null;
        if(profiles.spatial()!=null){geometry=spatial.prepare(claim.handoffId(),claim.payload(),profiles.spatial());if(!"OK".equals(geometry.status())){jobs.quarantine(claim,"UDP_SPATIAL_REVIEW_REQUIRED");return;}}
        var identity=profiles.identity();
        if(identity!=null&&profiles.spatial()!=null){
          var start=spatial.futureStart(claim.payload());
          if(start!=null){jobs.awaitValidity(claim,start);return;}
        }
        boolean reactivating=api && state.outcome==ApiObservation.Outcome.REACTIVATED;
        var coverage=reactivating && identity!=null?identityIndex.beforeBindingChange(identity):null;
        if(reactivating)state.changeBinding(true);
        var governedDecision=identity==null?null:governed.resolve(claim.handoffId(),claim.payload(),profiles.materialization(),identity);
        var legacyDecision=identity==null?resolution.resolve(claim.handoffId(),claim.payload(),profiles.resolution(),geometry):null;
        String outcome=identity==null?legacyDecision.outcome():governedDecision.outcome();
        if("REVIEW_REQUIRED".equals(outcome)){if(reactivating)state.changeBinding(false);jobs.quarantine(claim,"UDP_RESOLUTION_REVIEW_REQUIRED");return;}
        if(Set.of("MATCH","NEW_OBJECT").contains(outcome)&&!(identity!=null&&governedDecision.duplicate())){
          boolean reconcileIndex=identity!=null&&governedDecision.coverageRef()==null;
          if(reconcileIndex&&coverage==null&&(!governedDecision.resumed()||profiles.spatial()==null||!spatiallySuspended(claim.handoffId())))
            throw new IllegalStateException("UDP_IDENTITY_COVERAGE_UNVERIFIED");
          UUID target=identity==null?legacyDecision.targetUrbanObjectId():governedDecision.targetUrbanObjectId();
          db.sql("select urban_object_id from ouf_udp.urban_object where urban_object_id=:u for update").param("u",target).query(UUID.class).single();
          if(api && state.binding()!=null && !target.equals(state.binding()))throw new IllegalStateException("UDP_API_BINDING_DRIFT");
          if(profiles.spatial()!=null){
            String action=spatial.currentAction(claim.handoffId(),target,claim.payload(),profiles.spatial(),profiles.materialization(),geometry);
            if("REVIEW_REQUIRED".equals(action)){if(reactivating)state.changeBinding(false);jobs.quarantine(claim,"UDP_SPATIAL_AUTHORITY_CONFLICT");return;}
            if("FUTURE".equals(action)){if(reactivating)state.changeBinding(false);jobs.awaitValidity(claim,java.time.OffsetDateTime.parse(String.valueOf(HandoffIntakeService.object(claim.payload(),"sourceIdentity").get("validFrom"))));return;}
            if("HISTORICAL_ONLY".equals(action)){if(api){if(reactivating)state.changeBinding(false);jobs.quarantine(claim,"UDP_API_SPATIAL_VALIDITY_REVIEW");}else jobs.complete(claim);return;}
            if("ADVANCE".equals(action))spatial.materializePrepared(claim.handoffId(),target,claim.payload(),profiles.spatial(),geometry);
          }
          if(api){
            state.withdrawPrevious(target);
            spatial.refreshWithdrawn(target,claim.handoffId(),profiles.materialization());
          }
          materializer.materialize(claim.handoffId(),target,claim.payload(),profiles.materialization());
          if(identity!=null){
            // A pre-upgrade suspended NEW_OBJECT can itself have invalidated coverage.
            // Re-certify from current revisions before committing this resumed job.
            if(coverage!=null)identityIndex.refreshBindingChange(identity,target,coverage);
            else if(reconcileIndex)identityIndex.reconcileSpatialResume(identity);
            else identityIndex.refreshOne(identity,target,governedDecision.coverageRef());
          }
          if(relationships!=null)relationships.accept(claim.handoffId(),target,claim.payload(),profiles.relationships());
          if(api)state.finish(target);
        }else if(api){throw new IllegalStateException("UDP_API_IDENTITY_INCOMPLETE");}
        jobs.complete(claim);
      });
    }catch(SemanticReferenceFailure failure){gate.reject(claim,failure);
    }catch(PublicationAccessFailure failure){gate.transportFailure(claim,failure);
    }catch(RuntimeException failure){
      String code=Objects.toString(failure.getMessage(),"UDP_RESOLUTION_FAILED");
      if(Set.of("UDP_API_BINDING_DRIFT","UDP_SOURCE_EPOCH_CHANGED","UDP_SPATIAL_AUTHORITY_CONFLICT","UDP_API_PUBLICATION_SOURCE_MISMATCH").contains(code))
        jobs.quarantine(claim,code);
      else if(!"UDP_RESOLUTION_LEASE_LOST".equals(code))jobs.fail(claim,code);
      LOG.warn("UDP_AUTOMATIC_RESOLUTION_INCOMPLETE");
    }
  }
  private boolean spatiallySuspended(String handoff){
    return db.sql("select exists(select 1 from ouf_udp.handoff_event where handoff_id=:h and (event_type='GEOMETRY_VALIDITY_DEFERRED' or (event_type='REFERENCE_INTEGRITY_QUARANTINED' and safe_detail->>'safeFailureCode'='UDP_SPATIAL_AUTHORITY_CONFLICT')))")
        .param("h",handoff).query(Boolean.class).single();
  }
}

