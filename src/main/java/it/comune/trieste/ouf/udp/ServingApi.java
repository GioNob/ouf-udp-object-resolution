package it.comune.trieste.ouf.udp;

import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.JsonNode;
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
  // The mediated MCP request is JSON; keep the same owner-side admission and row filtering as GET.
  @PostMapping("/objects/search") GovernedServingService.Page<Map<String,Object>> searchPost(@RequestBody JsonNode body,HttpServletRequest r){
    if(body==null||!body.isObject()||body.size()>3||!body.has("type")||!body.get("type").isTextual()||body.get("type").asText().isBlank()||body.get("type").asText().length()>128
      ||body.has("pageSize")&&!body.get("pageSize").isIntegralNumber()
      ||body.has("cursor")&&(!body.get("cursor").isTextual()||body.get("cursor").asText().isBlank()||body.get("cursor").asText().length()>128))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UDP_INVALID_SEARCH_REQUEST");
    for(var names=body.fieldNames();names.hasNext();)if(!Set.of("type","pageSize","cursor").contains(names.next()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UDP_INVALID_SEARCH_REQUEST");
    return service.search(body.get("type").asText(),body.has("pageSize")?body.get("pageSize").asInt():50,body.has("cursor")?body.get("cursor").asText():null,context(r));
  }
  @GetMapping("/objects/{id}/relationships") GovernedServingService.Page<Map<String,Object>> relationships(@PathVariable UUID id,@RequestParam(defaultValue="50") int pageSize,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="OUTBOUND") String direction,HttpServletRequest r){return service.relationships(id,pageSize,cursor,direction,context(r));}
  @GetMapping("/objects/{id}/lineage") List<Map<String,Object>> lineage(@PathVariable UUID id,HttpServletRequest r){return service.lineage(id,null,context(r));}
  @GetMapping("/objects/{id}/properties/{property}/lineage") List<Map<String,Object>> propertyLineage(@PathVariable UUID id,@PathVariable String property,HttpServletRequest r){return service.lineage(id,property,context(r));}
  static ServingAuthorizationContext context(HttpServletRequest r){
    for(String h:FORBIDDEN)if(r.getHeader(h)!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UDP_UNTRUSTED_AUTHORIZATION_HEADER");
    try{var owner=it.comune.trieste.ouf.authorization.OwnerAuthorization.bind(r);
      String correlation=r.getAttribute("ouf.correlationId") instanceof String value&&!value.isBlank()?value:UUID.randomUUID().toString();
      return new ServingAuthorizationContext(owner.principal().actorType().name(),owner.principal().subjectId(),owner.principal().tenantId(),owner.candidates(),Set.of("OPEN","ANONYMOUS","PERSONAL","SENSITIVE","RESTRICTED"),owner.decisionRef(),correlation,owner);
    }catch(SecurityException e){throw new ResponseStatusException(HttpStatus.FORBIDDEN,e.getMessage());}
  }
}
