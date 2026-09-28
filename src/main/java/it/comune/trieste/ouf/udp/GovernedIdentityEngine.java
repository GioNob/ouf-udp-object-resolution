package it.comune.trieste.ouf.udp;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;

/** Deterministic identity decision core. Candidate retrieval and persistence are separate ports. */
public final class GovernedIdentityEngine {
  public enum ComparatorKind { CONCEPT, TEXT_V1, DECIMAL_V1 }
  public enum Outcome { MATCH, NEW_OBJECT, REVIEW_REQUIRED, RESOLUTION_TOO_BROAD }
  public enum EvidenceKind { AGREE, DISAGREE, MISSING }

  /** A semantic reference includes its publication/version; a coincidentally equal IRI is insufficient. */
  public record Signal(String id, String semanticRef, ComparatorKind comparator,
                       boolean excludesOnDisagreement, boolean uniqueWithinScope,
                       String assertionRef) {
    public Signal {
      if (blank(id) || blank(semanticRef) || comparator == null
          || ((excludesOnDisagreement || uniqueWithinScope) && blank(assertionRef))) throw invalid();
    }
  }
  /** All named signals must agree. Uniqueness is asserted by governance, never inferred from frequency. */
  public record SufficientRule(String id, Set<String> signalIds, String assertionRef) {
    public SufficientRule {
      if (blank(id) || signalIds == null || signalIds.isEmpty() || blank(assertionRef)) throw invalid();
      signalIds = Set.copyOf(signalIds);
    }
  }
  public record Policy(String ref, String version, String tenantId, String canonicalClass,
                       String sourceId, int maxCandidates, boolean allowAutoNew,
                       List<Signal> signals, List<SufficientRule> sufficientRules) {
    public Policy {
      if (blank(ref) || blank(version) || blank(tenantId) || blank(canonicalClass)
          || blank(sourceId) || maxCandidates < 1 || maxCandidates > 1000
          || signals == null || signals.isEmpty() || sufficientRules == null) throw invalid();
      signals = List.copyOf(signals);
      sufficientRules = List.copyOf(sufficientRules);
      Set<String> ids = new HashSet<>();
      for (Signal signal : signals) if (!ids.add(signal.id())) throw invalid();
      Set<String> rules = new HashSet<>();
      for (SufficientRule rule : sufficientRules) {
        if (!rules.add(rule.id()) || !ids.containsAll(rule.signalIds())) throw invalid();
        // A sufficient rule needs at least one governed uniqueness assertion.
        if (signals.stream().noneMatch(s -> rule.signalIds().contains(s.id()) && s.uniqueWithinScope()))
          throw invalid();
      }
    }
  }
  public record Value(String semanticRef, String raw, String provenanceRef) {
    public Value { if (blank(semanticRef) || raw == null || blank(provenanceRef)) throw invalid(); }
  }
  public record Subject(String tenantId, String canonicalClass, String sourceId,
                        Map<String, Value> values) {
    public Subject {
      if (blank(tenantId) || blank(canonicalClass) || blank(sourceId) || values == null) throw invalid();
      values = Map.copyOf(values);
    }
  }
  public record Candidate(UUID objectId, String tenantId, String canonicalClass,
                          Map<String, Value> values) {
    public Candidate {
      if (objectId == null || blank(tenantId) || blank(canonicalClass) || values == null) throw invalid();
      values = Map.copyOf(values);
    }
  }
  public record Evidence(String signalId, EvidenceKind kind, String subjectProvenance,
                         String candidateProvenance, String comparatorVersion, String assertionRef) {}
  public record Assessment(UUID objectId, List<Evidence> evidence, Set<String> satisfiedRules,
                           boolean excluded) {}
  public record Decision(Outcome outcome, UUID objectId, String reason,
                         String policyRef, String policyVersion, List<Assessment> assessments) {}
  /** Retrieval attests index coverage for the entire policy scope at a stable snapshot. */
  public record Candidates(String policyRef, String policyVersion, String tenantId,
                           String canonicalClass, String coverageRef, boolean complete,
                           List<Candidate> rows) {
    public Candidates {
      if (blank(policyRef) || blank(policyVersion) || blank(tenantId) || blank(canonicalClass)
          || rows == null || (complete && blank(coverageRef))) throw invalid();
      rows = List.copyOf(rows);
    }
  }
  public record Probe(Subject subject, Candidates retrieved) {}
  public record Preflight(int total, Map<Outcome, Long> outcomes, int largestCandidateSet) {}

  /** Preactivation and runtime call the same decision method. The caller supplies representative probes. */
  public Preflight preflight(Policy policy, List<Probe> probes) {
    if (probes == null || probes.isEmpty()) throw invalid();
    EnumMap<Outcome, Long> counts = new EnumMap<>(Outcome.class);
    int largest = 0;
    for (Probe probe : probes) {
      if (probe == null || probe.retrieved() == null) throw invalid();
      Decision decision = decide(policy, probe.subject(), probe.retrieved());
      counts.merge(decision.outcome(), 1L, Long::sum);
      largest = Math.max(largest, probe.retrieved().rows().size());
    }
    return new Preflight(probes.size(), Collections.unmodifiableMap(counts), largest);
  }

  /** The caller must query maxCandidates + 1 and attest complete index coverage. */
  public Decision decide(Policy policy, Subject subject, Candidates retrieval) {
    Objects.requireNonNull(policy); Objects.requireNonNull(subject); Objects.requireNonNull(retrieval);
    if (!policy.tenantId().equals(subject.tenantId())
        || !policy.canonicalClass().equals(subject.canonicalClass())
        || !policy.sourceId().equals(subject.sourceId())) throw invalid();
    if (!policy.ref().equals(retrieval.policyRef()) || !policy.version().equals(retrieval.policyVersion())
        || !policy.tenantId().equals(retrieval.tenantId())
        || !policy.canonicalClass().equals(retrieval.canonicalClass())) throw invalid();
    List<Candidate> retrieved = retrieval.rows();
    if (retrieved.size() > policy.maxCandidates())
      return result(policy, Outcome.RESOLUTION_TOO_BROAD, null, "CANDIDATE_LIMIT", List.of());
    if (!retrieval.complete())
      return result(policy, Outcome.REVIEW_REQUIRED, null, "CANDIDATE_COVERAGE_UNVERIFIED", List.of());
    Set<UUID> seen = new HashSet<>();
    List<Assessment> assessments = new ArrayList<>();
    for (Candidate candidate : retrieved) {
      if (!seen.add(candidate.objectId()) || !policy.tenantId().equals(candidate.tenantId())
          || !policy.canonicalClass().equals(candidate.canonicalClass())) throw invalid();
      List<Evidence> evidence = new ArrayList<>();
      Set<String> agreeing = new HashSet<>();
      boolean excluded = false;
      for (Signal signal : policy.signals()) {
        Value left = subject.values().get(signal.id()), right = candidate.values().get(signal.id());
        EvidenceKind kind;
        if (left == null || right == null || !signal.semanticRef().equals(left.semanticRef())
            || !signal.semanticRef().equals(right.semanticRef())) kind = EvidenceKind.MISSING;
        else if (normalize(signal.comparator(), left.raw()).equals(normalize(signal.comparator(), right.raw()))) {
          kind = EvidenceKind.AGREE; agreeing.add(signal.id());
        } else {
          kind = EvidenceKind.DISAGREE; excluded |= signal.excludesOnDisagreement();
        }
        evidence.add(new Evidence(signal.id(), kind, left == null ? null : left.provenanceRef(),
            right == null ? null : right.provenanceRef(), signal.comparator().name(), signal.assertionRef()));
      }
      Set<String> satisfied = new TreeSet<>();
      for (SufficientRule rule : policy.sufficientRules())
        if (agreeing.containsAll(rule.signalIds())) satisfied.add(rule.id());
      assessments.add(new Assessment(candidate.objectId(), List.copyOf(evidence), Set.copyOf(satisfied), excluded));
    }
    assessments.sort(Comparator.comparing(Assessment::objectId));
    List<Assessment> eligible = assessments.stream().filter(a -> !a.excluded()).toList();
    if (assessments.stream().anyMatch(a -> a.excluded() && !a.satisfiedRules().isEmpty()))
      return result(policy, Outcome.REVIEW_REQUIRED, null, "CONFLICTING_IDENTITY_EVIDENCE", assessments);
    if (eligible.isEmpty()) {
      if (policy.allowAutoNew()) return result(policy, Outcome.NEW_OBJECT, null, "SOURCE_SCOPED_CREATION", assessments);
      return result(policy, Outcome.REVIEW_REQUIRED, null, "CREATION_NOT_AUTHORIZED", assessments);
    }
    // Without a published property-authority decision, any conflicting shared value needs HUMAN review.
    if (eligible.size() == 1 && !eligible.get(0).satisfiedRules().isEmpty()
        && eligible.get(0).evidence().stream().noneMatch(e -> e.kind() == EvidenceKind.DISAGREE))
      return result(policy, Outcome.MATCH, eligible.get(0).objectId(), "SUFFICIENT_RULE", assessments);
    return result(policy, Outcome.REVIEW_REQUIRED, null, "UNRESOLVED_IDENTITY", assessments);
  }

  private static Decision result(Policy p, Outcome o, UUID target, String reason, List<Assessment> evidence) {
    return new Decision(o, target, reason, p.ref(), p.version(), List.copyOf(evidence));
  }
  private static String normalize(ComparatorKind kind, String raw) {
    return switch (kind) {
      case CONCEPT -> raw; // Published concept ID, exact and case sensitive.
      case TEXT_V1 -> Normalizer.normalize(raw, Normalizer.Form.NFKC).strip()
          .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
      case DECIMAL_V1 -> new BigDecimal(raw.strip()).stripTrailingZeros().toPlainString();
    };
  }
  private static boolean blank(String value) { return value == null || value.isBlank(); }
  private static IllegalArgumentException invalid() { return new IllegalArgumentException("UDP_IDENTITY_POLICY_INVALID"); }
}
