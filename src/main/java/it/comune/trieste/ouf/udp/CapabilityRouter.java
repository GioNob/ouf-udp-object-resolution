package it.comune.trieste.ouf.udp;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CapabilityRouter {
  private final QueryBudgetStore budgets;

  public CapabilityRouter(QueryBudgetStore budgets) { this.budgets = budgets; }

  public void authorizeTraverse(TraversalPurpose purpose, ServingAuthorizationContext auth) {
    auth.require("urban.graph.traverse");
    if (purpose == null) throw new IllegalArgumentException("UDP_TRAVERSAL_PURPOSE_REQUIRED");
    if (purpose == TraversalPurpose.RECURSIVE_HIERARCHY) return;

    Remediation remediation = switch (purpose) {
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
    budgets.mismatch(auth, "GRAPH");
    throw new CapabilityMismatchException(remediation);
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
}
