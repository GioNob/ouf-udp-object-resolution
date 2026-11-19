package it.comune.trieste.ouf.udp;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

/** Stable non-sensitive signal for a request rejected before owner invocation. */
@RestControllerAdvice(assignableTypes=TrustedHumanApi.class)
public class ResolutionRequestErrors {
 @ExceptionHandler(HttpMessageNotReadableException.class)
 ResponseEntity<Map<String,Object>> unreadable(HttpMessageNotReadableException ignored){
  return ResponseEntity.badRequest().body(Map.of("status",400,"code","UDP_REVIEW_REQUEST_INVALID"));
 }
}
