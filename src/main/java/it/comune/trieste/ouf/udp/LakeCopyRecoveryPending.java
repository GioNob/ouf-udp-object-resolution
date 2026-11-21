package it.comune.trieste.ouf.udp;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

class LakeCopyRecoveryPending extends IllegalStateException {
  LakeCopyRecoveryPending() { super("UDP_LAKE_COPY_RECOVERY_PENDING_CONTRACT"); }
}

@RestControllerAdvice(assignableTypes={RuntimeLakeApi.class,HandoffApi.class})
class LakeCopyRecoveryErrors {
  @ExceptionHandler(LakeCopyRecoveryPending.class)
  ResponseEntity<Map<String,String>> pending(LakeCopyRecoveryPending error) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code",error.getMessage()));
  }
}
