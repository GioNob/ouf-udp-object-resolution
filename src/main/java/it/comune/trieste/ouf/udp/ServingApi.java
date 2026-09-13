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
  @SuppressWarnings("unchecked") static ServingAuthorizationContext context(HttpServletRequest r){for(String h:FORBIDDEN)if(r.getHeader(h)!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UDP_UNTRUSTED_AUTHORIZATION_HEADER");Object type=r.getAttribute("ouf.principalType"),subject=r.getAttribute("ouf.subject"),tenant=r.getAttribute("ouf.tenantId"),caps=r.getAttribute("ouf.capabilities"),labels=r.getAttribute("ouf.allowedDataLabels"),decision=r.getAttribute("ouf.authorizationDecisionRef"),correlation=r.getAttribute("ouf.correlationId");if(type==null||subject==null||tenant==null||!(caps instanceof Set<?>)||!(labels instanceof Set<?>)||decision==null||correlation==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"UDP_AUTHORIZATION_CONTEXT_REQUIRED");return new ServingAuthorizationContext(String.valueOf(type),String.valueOf(subject),String.valueOf(tenant),(Set<String>)caps,(Set<String>)labels,String.valueOf(decision),String.valueOf(correlation));}
}
