package it.comune.trieste.ouf.udp;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class CapabilityRouterErrorHandler {
  @ExceptionHandler(CapabilityMismatchException.class)
  ResponseEntity<CapabilityRouter.Remediation> mismatch(CapabilityMismatchException exception) {
    return ResponseEntity.unprocessableEntity().body(exception.remediation());
  }
}
