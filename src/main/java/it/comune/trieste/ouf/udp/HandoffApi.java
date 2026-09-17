package it.comune.trieste.ouf.udp;

import java.net.URI;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/internal/v1/handoffs")
public class HandoffApi {
  private final HandoffIntakeService service;private final RuntimeIntakeAuthorization authorization;public HandoffApi(HandoffIntakeService service,RuntimeIntakeAuthorization authorization){this.service=service;this.authorization=authorization;}
  @PostMapping ResponseEntity<HandoffIntakeService.Receipt> accept(@RequestBody Map<String,Object> payload,HttpServletRequest request){var source=HandoffIntakeService.object(payload,"sourceIdentity");authorization.require(request,"udp.candidate.write",source==null?null:HandoffIntakeService.text(source,"sourceId"),HandoffIntakeService.text(payload,"ingestionRunId"));var receipt=service.accept(payload);return ResponseEntity.created(URI.create("/api/internal/v1/handoffs/"+payload.get("handoffId"))).body(receipt);}
  @GetMapping("/{handoffId}") Map<String,Object> get(@PathVariable String handoffId,HttpServletRequest request){authorization.admit(request,"udp.candidate.write");var row=service.handoff(handoffId);authorization.require(request,"udp.candidate.write",String.valueOf(row.get("source_id")),String.valueOf(row.get("ingestion_run_id")));return row;}
}
