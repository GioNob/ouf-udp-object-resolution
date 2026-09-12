package it.comune.trieste.ouf.udp;

import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/internal/v1/handoffs")
public class HandoffApi {
  private final HandoffIntakeService service;public HandoffApi(HandoffIntakeService service){this.service=service;}
  @PostMapping ResponseEntity<HandoffIntakeService.Receipt> accept(@RequestBody Map<String,Object> payload){var receipt=service.accept(payload);return ResponseEntity.created(URI.create("/api/internal/v1/handoffs/"+payload.get("handoffId"))).body(receipt);}
  @GetMapping("/{handoffId}") Map<String,Object> get(@PathVariable String handoffId){return service.handoff(handoffId);}
}
