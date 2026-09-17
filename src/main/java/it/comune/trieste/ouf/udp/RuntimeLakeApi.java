package it.comune.trieste.ouf.udp;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/internal/v1/lake/objects")
public class RuntimeLakeApi {
  private final RuntimeIntakeAuthorization authorization;private final LakeLifecycleService lake;
  private final int retentionDays;private final String retentionClass,accessLabel;
  public RuntimeLakeApi(RuntimeIntakeAuthorization authorization,LakeLifecycleService lake,@Value("${ouf.udp.lake.raw-retention-days:0}") int retentionDays,@Value("${ouf.udp.lake.raw-retention-class:}") String retentionClass,@Value("${ouf.udp.lake.raw-access-label:}") String accessLabel){this.authorization=authorization;this.lake=lake;this.retentionDays=retentionDays;this.retentionClass=retentionClass;this.accessLabel=accessLabel;}
  @PostMapping public ResponseEntity<Map<String,Object>> store(@RequestBody Write body,HttpServletRequest request){
    String tenant=authorization.require(request,"datalake.write",body.sourceId(),body.runId());
    if(retentionDays<1||retentionDays>36500||!Set.of("OPEN","ANONYMOUS","PERSONAL","SENSITIVE","RESTRICTED").contains(accessLabel))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"UDP_LAKE_POLICY_REQUIRED");
    if(body.sourceId()==null||body.sourceId().isBlank()||body.typeCode()==null||body.typeCode().isBlank()||body.contentBase64()==null||body.contentBase64().length()>66_666_668||(body.zone()==null||!Set.of("RAW","NORMALIZED","CURATED").contains(body.zone())))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UDP_LAKE_INPUT_INVALID");
    UUID.fromString(body.runId());byte[] content;try{content=Base64.getDecoder().decode(body.contentBase64());}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UDP_LAKE_INPUT_INVALID");}
    if(content.length>50_000_000||!hash(content).equals(body.contentHash()))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"UDP_LAKE_HASH_MISMATCH");
    var object=lake.store(content,tenant,body.sourceId(),body.typeCode(),body.zone(),"application/json",retentionClass,accessLabel,OffsetDateTime.now().plusDays(retentionDays),body.runId());
    if(!"VERIFIED".equals(object.state()))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"UDP_LAKE_NOT_DURABLE");
    String ref="lake://"+object.id();return ResponseEntity.created(URI.create(ref)).body(Map.of("objectRef",ref,"contentHash",object.hash(),"durable",true));
  }
  static String hash(byte[] value){try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw new IllegalStateException("UDP_HASH_FAILED",e);}}
  public record Write(String runId,String sourceId,String typeCode,String sourceObjectId,String zone,String contentBase64,String contentHash,String idempotencyKey){}
}
