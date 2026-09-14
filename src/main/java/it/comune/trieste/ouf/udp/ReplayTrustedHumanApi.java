package it.comune.trieste.ouf.udp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/udp/v1/governance/replays")
public class ReplayTrustedHumanApi {
  private final HistoricalReplayService service;public ReplayTrustedHumanApi(HistoricalReplayService service){this.service=service;}
  @PostMapping HistoricalReplayService.Plan plan(@RequestBody PlanRequest body,HttpServletRequest request){return service.plan(body.sourceHandoffId(),body.reason(),TrustedHumanApi.trusted(request));}
  @PostMapping("/{id}/resume") HistoricalReplayService.Plan resume(@PathVariable UUID id,@RequestBody VersionRequest body,HttpServletRequest request){return service.resume(id,body.expectedVersion(),TrustedHumanApi.trusted(request));}
  @PostMapping("/{id}/execute") HistoricalReplayService.Plan execute(@PathVariable UUID id,@RequestBody VersionRequest body,HttpServletRequest request){return service.execute(id,body.expectedVersion(),TrustedHumanApi.trusted(request));}
  @PostMapping("/{id}/abort") HistoricalReplayService.Plan abort(@PathVariable UUID id,@RequestBody AbortRequest body,HttpServletRequest request){return service.abort(id,body.expectedVersion(),body.reason(),TrustedHumanApi.trusted(request));}
  public record PlanRequest(String sourceHandoffId,String reason){}
  public record VersionRequest(long expectedVersion){}
  public record AbortRequest(long expectedVersion,String reason){}
}
