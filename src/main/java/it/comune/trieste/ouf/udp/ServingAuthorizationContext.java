package it.comune.trieste.ouf.udp;

import java.util.Set;

public record ServingAuthorizationContext(String principalType,String subject,String tenantId,Set<String> capabilities,Set<String> allowedDataLabels,String authorizationDecisionRef,String correlationId){
  public ServingAuthorizationContext{capabilities=Set.copyOf(capabilities);allowedDataLabels=Set.copyOf(allowedDataLabels);}
  public void require(String capability){if(!capabilities.contains(capability)||authorizationDecisionRef==null||authorizationDecisionRef.isBlank())throw new SecurityException("UDP_AUTHORIZATION_DENIED");}
  public boolean permits(String label){return allowedDataLabels.contains(label);}
}
