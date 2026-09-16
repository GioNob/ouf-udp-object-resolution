package it.comune.trieste.ouf.udp;

import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/udp/v1")
public class ServingApi {
  private static final List<String> FORBIDDEN=List.of("X-Principal-Type","X-Principal-Subject","X-Tenant-Id","X-Capabilities","X-Allowed-Data-Labels","X-Authorization-Decision-Ref");
  private final GovernedServingService service;public ServingApi(GovernedServingService service){this.service=service;}
  @GetMapping("/objects/{id}") Map<String,Object> current(@PathVariable UUID id,HttpServletRequest r){return service.current(id,context(r));}
  @GetMapping("/objects/{id}/history") GovernedServingService.Page<Map<String,Object>> history(@PathVariable UUID id,@RequestParam(defaultValue="50") int pageSize,@RequestParam(required=false) String cursor,@RequestParam(required=false) OffsetDateTime asOf,HttpServletRequest r){return service.history(id,pageSize,cursor,asOf,context(r));}
  @GetMapping("/objects") GovernedServingService.Page<Map<String,Object>> search(@RequestParam String type,@RequestParam(defaultValue="50") int pageSize,@RequestParam(required=false) String cursor,HttpServletRequest r){return service.search(type,pageSize,cursor,context(r));}
  @GetMapping("/objects/{id}/relationships") GovernedServingService.Page<Map<String,Object>> relationships(@PathVariable UUID id,@RequestParam(defaultValue="50") int pageSize,@RequestParam(required=false) String cursor,HttpServletRequest r){return service.relationships(id,pageSize,cursor,context(r));}
  @GetMapping("/objects/{id}/lineage") List<Map<String,Object>> lineage(@PathVariable UUID id,HttpServletRequest r){return service.lineage(id,null,context(r));}
  @GetMapping("/objects/{id}/properties/{property}/lineage") List<Map<String,Object>> propertyLineage(@PathVariable UUID id,@PathVariable String property,HttpServletRequest r){return service.lineage(id,property,context(r));}
  static ServingAuthorizationContext context(HttpServletRequest r){
    for(String h:FORBIDDEN)if(r.getHeader(h)!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UDP_UNTRUSTED_AUTHORIZATION_HEADER");
    try{var c=it.comune.trieste.ouf.authorization.ServletAuthorization.resolve(r);
      Set<String> labels=new HashSet<>();Object raw=r.getAttribute("ouf.allowedDataLabels");if(raw instanceof Set<?> values)for(Object v:values)if(v instanceof String label)labels.add(label);
      String correlation=r.getAttribute("ouf.correlationId") instanceof String value&&!value.isBlank()?value:UUID.randomUUID().toString();
      return new ServingAuthorizationContext(c.principal().actorType().name(),c.principal().subjectId(),c.principal().tenantId(),c.capabilities(),labels,c.decisionRef(),correlation);
    }catch(SecurityException e){throw new ResponseStatusException(HttpStatus.FORBIDDEN,e.getMessage());}
  }
}
