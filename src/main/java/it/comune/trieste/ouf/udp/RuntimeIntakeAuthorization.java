package it.comune.trieste.ouf.udp;

import it.comune.trieste.ouf.authorization.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RuntimeIntakeAuthorization {
  private final String tenant;
  private final String ingestionWorkload;
  @org.springframework.beans.factory.annotation.Autowired
  public RuntimeIntakeAuthorization(@Value("${ouf.udp.lake.tenant-id:}") String tenant,
      @Value("${ouf.udp.ingestion-workload:}") String ingestionWorkload){
    this.tenant=tenant; this.ingestionWorkload=ingestionWorkload;
  }
  RuntimeIntakeAuthorization(String tenant){this(tenant,"");}
  String requireOutcomes(HttpServletRequest request,String source,String run){
    admit(request,"udp.candidate.write");
    if(ingestionWorkload.isBlank() || !ingestionWorkload.equals(OwnerAuthorization.bind(request).principal().servicePrincipalId()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"UDP_RUNTIME_INTAKE_DENIED");
    return require(request,"udp.candidate.write",source,run);
  }
  public void admit(HttpServletRequest request,String capability){
    try{var owner=OwnerAuthorization.bind(request);if(tenant.isBlank()||!tenant.equals(owner.principal().tenantId())||owner.principal().actorType()!=PrincipalContext.ActorType.SERVICE||!owner.candidates().contains(capability))throw new SecurityException();}
    catch(SecurityException failure){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"UDP_RUNTIME_INTAKE_DENIED");}
  }
  public String require(HttpServletRequest request,String capability,String source,String run){
    try{
      var owner=OwnerAuthorization.bind(request);
      if(tenant.isBlank()||owner.principal().actorType()!=PrincipalContext.ActorType.SERVICE)throw new SecurityException();
      var scope=new LinkedHashMap<String,String>();scope.put("module","UDP");if(source!=null)scope.put("sourceRef",source);if(run!=null)scope.put("jobRef",run);
      owner.require(capability,new ResourceContext("ingestion-intake",source,tenant,null,scope));return tenant;
    }catch(SecurityException failure){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"UDP_RUNTIME_INTAKE_DENIED");}
  }
}
