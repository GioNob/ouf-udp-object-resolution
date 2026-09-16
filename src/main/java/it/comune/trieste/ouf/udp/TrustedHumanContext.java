package it.comune.trieste.ouf.udp;

import java.util.Set;

public record TrustedHumanContext(String actorType,String subject,String tenantId,Set<String> capabilities,String authorizationDecisionRef,String correlationId){
  public TrustedHumanContext{// Legacy constructor input is an internal/domain compatibility bridge, never a token claim mapper.
    if("HUMAN_USER".equals(actorType))actorType="HUMAN";
    capabilities=Set.copyOf(capabilities);}
  public void require(String capability){if(!"HUMAN".equals(actorType)||!capabilities.contains(capability)||authorizationDecisionRef==null||authorizationDecisionRef.isBlank())throw new SecurityException("UDP_TRUSTED_HUMAN_REQUIRED");}
}
