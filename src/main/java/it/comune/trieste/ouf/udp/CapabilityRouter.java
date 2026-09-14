package it.comune.trieste.ouf.udp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CapabilityRouter {
  private final QueryBudgetStore budgets;
  private final CapabilityRetryGuard retryGuard;
  private final int mismatchThreshold;

  public CapabilityRouter(QueryBudgetStore budgets, CapabilityRetryGuard retryGuard,
      @Value("${ouf.udp.capability-router.mismatch-threshold:3}") int mismatchThreshold) {
    if (mismatchThreshold < 1) throw new IllegalArgumentException("UDP_RETRY_THRESHOLD_INVALID");
    this.budgets = budgets;
    this.retryGuard = retryGuard;
    this.mismatchThreshold = mismatchThreshold;
  }

  public void authorizeTraverse(TraversalIntent intent, ServingAuthorizationContext auth) {
    auth.require("urban.graph.traverse");
    TraversalPurpose purpose = intent == null ? null : intent.purpose();
    if (purpose == null) throw new IllegalArgumentException("UDP_TRAVERSAL_PURPOSE_REQUIRED");
    if (purpose == TraversalPurpose.RECURSIVE_HIERARCHY) return;

    Remediation remediation = switch (purpose) {
      case RECURSIVE_HIERARCHY -> throw new IllegalStateException("UDP_ROUTER_UNREACHABLE_RECURSIVE_CASE");
      case CROSS_DOMAIN_RELATIONSHIP -> mismatch(
          "urban.object.related_search",
          List.of("startObjectId", "relationTypes"),
          List.of("anchorType", "targetTypes", "direction"));
      case CROSS_DOMAIN_SPATIAL -> mismatch(
          "urban.spatial.intersection_search",
          List.of("startObjectId"),
          List.of("targetType"));
      case EXPLORATORY_ONE_HOP -> new Remediation(
          "QUERY_CAPABILITY_MISMATCH", "urban.graph.traverse",
          "NON_RECURSIVE_EXPLORATORY_PATTERN", "urban.graph.neighbors", true,
          List.of("startObjectId", "relationTypes", "maxNodes", "maxEdges"), List.of(),
          "capability://urban.graph.neighbors/input-schema",
          new BudgetImpact("NONE", "ONE_MISMATCH"));
    };
    String fingerprint = semanticFingerprint(intent);
    CapabilityRetryGuard.Snapshot retry = retryGuard.register(
        auth, "urban.graph.traverse", fingerprint, mismatchThreshold);
    if (retry.stalled()) {
      throw new ToolSelectionStalledException(new StalledResponse(
          "TOOL_SELECTION_STALLED", "urban.graph.traverse",
          "EQUIVALENT_RETRY_THRESHOLD_REACHED", retry.attempts(), retry.threshold(), false,
          List.of("NARROW_REQUEST", "ASK_USER_CLARIFICATION", "USE_ANALYTICAL_CAPABILITY", "STOP"),
          new BudgetImpact("NONE", "STALLED_REJECTION")));
    }
    budgets.mismatch(auth, "GRAPH");
    throw new CapabilityMismatchException(remediation);
  }

  String semanticFingerprint(TraversalIntent intent) {
    List<String> relations = intent.relationTypes() == null ? List.of()
        : intent.relationTypes().stream().filter(Objects::nonNull).map(String::strip)
            .distinct().sorted().toList();
    String canonical = "urban.graph.traverse\n" + intent.purpose() + "\n"
        + intent.startObjectId() + "\n" + String.join("\n", relations);
    try {
      return "sha256:" + HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("UDP_RETRY_FINGERPRINT_FAILED", exception);
    }
  }

  private static Remediation mismatch(String recommended, List<String> reusable,
                                      List<String> missing) {
    return new Remediation("QUERY_CAPABILITY_MISMATCH", "urban.graph.traverse",
        "CROSS_DOMAIN_NON_RECURSIVE_PATTERN", recommended, true, reusable, missing,
        "capability://" + recommended + "/input-schema",
        new BudgetImpact("NONE", "ONE_MISMATCH"));
  }

  public enum TraversalPurpose {
    RECURSIVE_HIERARCHY,
    CROSS_DOMAIN_RELATIONSHIP,
    CROSS_DOMAIN_SPATIAL,
    EXPLORATORY_ONE_HOP
  }

  public record TraversalIntent(UUID startObjectId, List<String> relationTypes,
                                TraversalPurpose purpose) {
    public TraversalIntent {
      relationTypes = relationTypes == null ? null
          : Collections.unmodifiableList(new ArrayList<>(relationTypes));
    }
  }
  public record BudgetImpact(String queryExecution, String agentOrchestration) {}
  public record Remediation(String code, String requestedCapability, String reason,
                            String recommendedCapability, boolean retryable,
                            List<String> reusableArguments, List<String> missingArguments,
                            String recommendedArgumentShapeRef, BudgetImpact budgetImpact) {
    public Remediation {
      reusableArguments = List.copyOf(reusableArguments);
      missingArguments = List.copyOf(missingArguments);
    }
  }
  public record StalledResponse(String code, String requestedCapability, String reason,
                                int attempts, int threshold, boolean retryable,
                                List<String> governedActions, BudgetImpact budgetImpact) {
    public StalledResponse { governedActions = List.copyOf(governedActions); }
  }
}
