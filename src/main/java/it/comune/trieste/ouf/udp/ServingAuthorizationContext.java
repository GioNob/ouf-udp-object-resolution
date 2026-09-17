package it.comune.trieste.ouf.udp;

import java.util.*;
import it.comune.trieste.ouf.authorization.*;

public record ServingAuthorizationContext(String principalType,String subject,String tenantId,Set<String> capabilities,Set<String> allowedDataLabels,String authorizationDecisionRef,String correlationId,OwnerAuthorization owner){
  public ServingAuthorizationContext(String type,String subject,String tenant,Set<String> caps,Set<String> labels,String ref,String correlation){this(type,subject,tenant,caps,labels,ref,correlation,null);}
  public ServingAuthorizationContext{capabilities=Set.copyOf(capabilities);allowedDataLabels=Set.copyOf(allowedDataLabels);}
  public void require(String capability){if(!capabilities.contains(capability)||authorizationDecisionRef==null||authorizationDecisionRef.isBlank())throw new SecurityException("UDP_AUTHORIZATION_DENIED");}
  public boolean permits(String label){return owner==null&&allowedDataLabels.contains(label);}
  public boolean permits(String capability,String type,Object id,String label,Map<String,String> attributes){
    if(owner==null)return capabilities.contains(capability)&&label!=null&&allowedDataLabels.contains(label);
    var scope=new HashMap<>(attributes);scope.put("module","UDP");scope.put("requiresDataAccessLabel","true");if(label!=null)scope.put("dataAccessLabel",label);
    return owner.decide(capability,new ResourceContext(type,id==null?null:id.toString(),tenantId,null,scope)).allowed();
  }
  public void requireResource(String capability,String type,Object id,String label,Map<String,String> attributes){if(!permits(capability,type,id,label,attributes))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"UDP_RESOURCE_NOT_AUTHORIZED");}
  public String decisionRef(String capability){return owner==null?authorizationDecisionRef:owner.decisionRef()+":"+capability;}
}
