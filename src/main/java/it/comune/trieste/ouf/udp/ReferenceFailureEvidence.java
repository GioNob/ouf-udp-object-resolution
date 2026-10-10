package it.comune.trieste.ouf.udp;

import java.util.*;

/** Retain only explicitly safe symbolic diagnostics, never raw exception messages. */
final class ReferenceFailureEvidence {
  private static final Set<String> CODES=Set.of("UDP_PINNED_PROFILE_INVALID",
      "UDP_GOVERNED_IDENTITY_PROFILE_INVALID","UDP_RESOLUTION_PROFILE_UNSUPPORTED",
      "UDP_GATEWAY_INVALID","UDP_CONTRACT_REFS_REQUIRED","UDP_CONTRACT_REF_REQUIRED",
      "UDP_CONTRACT_REF_INVALID","UDP_HISTORICAL_CATALOG_INVALID","UDP_HISTORICAL_CATALOG_DUPLICATE",
      "UDP_HISTORICAL_BASELINE_INVALID","UDP_PUBLICATION_UNAVAILABLE","UDP_PUBLICATION_INTERRUPTED");
  static Map<String,Object> detail(RuntimeException failure){
    String message=failure.getMessage();
    String code=message!=null&&CODES.contains(message)?message:"UNCLASSIFIED";
    List<String> frames=Arrays.stream(failure.getStackTrace())
        .filter(f->f.getClassName().startsWith("it.comune.trieste.ouf.udp.")
            &&f.getClassName().matches("[A-Za-z0-9_.$]{1,240}")
            &&f.getMethodName().matches("[A-Za-z0-9_$<>]{1,160}"))
        .limit(4).map(f->f.getClassName()+"#"+f.getMethodName()+":"+f.getLineNumber()).toList();
    return Map.of("diagnosticCode",code,"diagnosticCategory",
        failure instanceof IllegalArgumentException?"IllegalArgumentException":"IllegalStateException",
        "diagnosticFrames",frames);
  }
  private ReferenceFailureEvidence(){}
}
