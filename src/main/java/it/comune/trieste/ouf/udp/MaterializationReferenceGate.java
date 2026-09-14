package it.comune.trieste.ouf.udp;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MaterializationReferenceGate {
  private final HistoricalContractCatalog catalog;private final ResolutionRepository jobs;private final int maxAttempts;private final long retryBaseMillis;
  public MaterializationReferenceGate(HistoricalContractCatalog catalog,ResolutionRepository jobs,@Value("${ouf.udp.reference-integrity.max-attempts:5}") int maxAttempts,@Value("${ouf.udp.reference-integrity.retry-base-ms:30000}") long retryBaseMillis){if(maxAttempts<1||retryBaseMillis<1)throw new IllegalArgumentException("UDP_REFERENCE_INTEGRITY_CONFIG_INVALID");this.catalog=catalog;this.jobs=jobs;this.maxAttempts=maxAttempts;this.retryBaseMillis=retryBaseMillis;}
  public boolean verify(ResolutionRepository.Claim claim){
    HistoricalContractCatalog.Resolution result;
    try{
      Map<String,Object> refs=HandoffIntakeService.object(claim.payload(),"contractRefs");result=catalog.resolve(refs);
    }catch(IllegalArgumentException e){jobs.quarantine(claim,"UDP_REFERENCE_INTEGRITY_CONTRACT_INVALID");return false;}catch(IllegalStateException e){jobs.quarantine(claim,"UDP_REFERENCE_INTEGRITY_CATALOG_INVALID");return false;}
    if(!result.ready()){jobs.pause(claim,result.missingRefHashes(),delay(claim.integrityAttempts()),maxAttempts);return false;}
    if(claim.integrityBaselineHash()!=null&&!claim.integrityBaselineHash().equals(result.baselineHash())){jobs.quarantine(claim,"UDP_REFERENCE_INTEGRITY_DRIFT");return false;}
    jobs.integrityPassed(claim,result.baselineHash());return true;
  }
  private long delay(int attempts){long factor=1L<<Math.min(attempts,10);return retryBaseMillis>3_600_000L/factor?3_600_000L:retryBaseMillis*factor;}
}
