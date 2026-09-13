package it.comune.trieste.ouf.udp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/udp/v1/governance/lake")
public class LakeTrustedHumanApi {
  private final LakeLifecycleService lifecycle;public LakeTrustedHumanApi(LakeLifecycleService lifecycle){this.lifecycle=lifecycle;}
  @PostMapping("/objects/{id}/deletion") LakeCatalogRepository.LakeObject authorizeDeletion(@PathVariable UUID id,@RequestBody DeletionRequest body,HttpServletRequest request){return lifecycle.authorizeDeletion(id,body.expectedVersion(),body.reason(),TrustedHumanApi.trusted(request));}
  public record DeletionRequest(long expectedVersion,String reason){}
}
