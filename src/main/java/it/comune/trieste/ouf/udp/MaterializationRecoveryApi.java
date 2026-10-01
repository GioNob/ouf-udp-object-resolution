package it.comune.trieste.ouf.udp;

import it.comune.trieste.ouf.authorization.OwnerAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@RestController
@ConditionalOnProperty(name="ouf.udp.materialization-recovery.enabled",havingValue="true")
@RequestMapping("/api/udp/v1/governance/materialization/jobs")
public class MaterializationRecoveryApi {
  private final MaterializationRecoveryService recovery;
  public MaterializationRecoveryApi(MaterializationRecoveryService recovery){this.recovery=recovery;}
  @GetMapping("/{id}")
  MaterializationRecoveryService.Review review(@PathVariable UUID id,HttpServletRequest request){
    var actor=TrustedHumanApi.trusted(request);var owner=OwnerAuthorization.bind(request);
    return recovery.review(id,actor,resource->owner.require(MaterializationRecoveryService.CAPABILITY,resource));
  }
  @PostMapping("/{id}/retry")
  MaterializationRecoveryService.Receipt retry(@PathVariable UUID id,
      @RequestBody MaterializationRecoveryService.Request body,HttpServletRequest request){
    var actor=TrustedHumanApi.trusted(request);var owner=OwnerAuthorization.bind(request);
    return recovery.retry(id,body,actor,resource->owner.require(MaterializationRecoveryService.CAPABILITY,resource));
  }
}
