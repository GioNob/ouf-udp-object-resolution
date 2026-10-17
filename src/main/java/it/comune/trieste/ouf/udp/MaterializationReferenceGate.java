package it.comune.trieste.ouf.udp;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MaterializationReferenceGate {
  private final HistoricalContractCatalog catalog;private final ResolutionRepository jobs;private final int maxAttempts;private final long retryBaseMillis;
  public MaterializationReferenceGate(HistoricalContractCatalog catalog,ResolutionRepository jobs,@Value("${ouf.udp.reference-integrity.max-attempts:5}") int maxAttempts,@Value("${ouf.udp.reference-integrity.retry-base-ms:30000}") long retryBaseMillis){if(maxAttempts<1||retryBaseMillis<1)throw new IllegalArgumentException("UDP_REFERENCE_INTEGRITY_CONFIG_INVALID");this.catalog=catalog;this.jobs=jobs;this.maxAttempts=maxAttempts;this.retryBaseMillis=retryBaseMillis;}
  @org.springframework.beans.factory.annotation.Autowired private PublicationDependencyRecovery dependencies;
  public boolean verify(ResolutionRepository.Claim claim){
    if(!dependencies.beforeAttempt(claim))return false;
    HistoricalContractCatalog.Resolution result;
    try{
      Map<String,Object> refs=HandoffIntakeService.object(claim.payload(),"contractRefs");result=catalog.resolve(refs);
    }catch(PublicationAccessFailure failure){
      if(failure.retryable())dependencies.defer(claim,failure);else jobs.quarantine(claim,failure.getMessage());
      return false;
    }catch(IllegalArgumentException e){jobs.quarantine(claim,"UDP_REFERENCE_INTEGRITY_CONTRACT_INVALID",ReferenceFailureEvidence.detail(e));return false;}catch(IllegalStateException e){jobs.quarantine(claim,"UDP_REFERENCE_INTEGRITY_CATALOG_INVALID",ReferenceFailureEvidence.detail(e));return false;}
    if(!result.ready()){jobs.pause(claim,result.missingRefHashes(),delay(claim.integrityAttempts()),maxAttempts);return false;}
    if(claim.integrityBaselineHash()!=null&&!claim.integrityBaselineHash().equals(result.baselineHash())){jobs.quarantine(claim,"UDP_REFERENCE_INTEGRITY_DRIFT");return false;}
    jobs.integrityPassed(claim,result.baselineHash());return true;
  }
  public void profilesReady(ResolutionRepository.Claim claim){dependencies.recovered(claim);}
  public void transportFailure(ResolutionRepository.Claim claim,PublicationAccessFailure failure){
    if(failure.retryable())dependencies.defer(claim,failure);else jobs.quarantine(claim,failure.getMessage());
  }
  private long delay(int attempts){long factor=1L<<Math.min(attempts,10);return retryBaseMillis>3_600_000L/factor?3_600_000L:retryBaseMillis*factor;}
}
