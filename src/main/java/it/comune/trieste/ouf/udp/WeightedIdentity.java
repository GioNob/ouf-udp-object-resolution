package it.comune.trieste.ouf.udp;

import java.text.Normalizer;
import java.util.*;

/** PET UDP §§22–24: deterministic, bounded scoring; a score is evidence, not authority. */
public final class WeightedIdentity {
  private WeightedIdentity() {}
  public record Signal(String property, String comparator, double weight, Double tolerance) {
    public Signal {
      if (property == null || property.isBlank() || !Set.of("EXACT", "TEXT", "NUMBER", "DISTANCE", "OVERLAP").contains(comparator)
          || !Double.isFinite(weight) || weight <= 0 || weight > 1) throw invalid();
      if (Set.of("NUMBER", "DISTANCE").contains(comparator)
          && (tolerance == null || !Double.isFinite(tolerance) || tolerance <= 0)) throw invalid();
    }
    boolean spatial() { return comparator.equals("DISTANCE") || comparator.equals("OVERLAP"); }
  }
  public record Policy(List<Signal> signals, List<String> blockingProperties, Double blockingDistanceMeters,
      int maxCandidates, double highThreshold, double reviewThreshold, double minimumMargin,
      boolean allowSpatialIdentity) {
    public Policy {
      signals = List.copyOf(signals); blockingProperties = List.copyOf(blockingProperties);
      if (signals.isEmpty() || signals.size() > 16 || blockingProperties.size() > 8
          || new HashSet<>(blockingProperties).size() != blockingProperties.size()
          || blockingProperties.stream().anyMatch(p -> p == null || p.isBlank())
          || maxCandidates < 1 || maxCandidates > 100
          || !unit(highThreshold) || highThreshold == 0 || !unit(reviewThreshold) || reviewThreshold >= highThreshold
          || !unit(minimumMargin) || minimumMargin == 0
          || Math.abs(signals.stream().mapToDouble(Signal::weight).sum() - 1) > 1e-9
          || signals.stream().map(s -> s.property() + ":" + s.comparator()).distinct().count() != signals.size()) throw invalid();
      if (blockingDistanceMeters != null && (!Double.isFinite(blockingDistanceMeters) || blockingDistanceMeters <= 0 || blockingDistanceMeters > 10000)) throw invalid();
      if (blockingProperties.isEmpty() && blockingDistanceMeters == null) throw invalid();
    }
  }
  public record Candidate(UUID id, double score, Map<String, Double> signals, boolean nonSpatialEvidence,
      List<String> missingSignals) {}
  public record Outcome(String outcome, UUID target, String reason, List<Candidate> candidates) {}

  public static Candidate score(UUID id, Map<String, Object> incoming, Map<String, Object> existing,
      Map<String, Double> spatialFacts, Policy policy) {
    var evidence = new LinkedHashMap<String, Double>();
    var missing = new ArrayList<String>(); double total = 0; boolean nonSpatial = false;
    for (Signal signal : policy.signals()) {
      double value;
      if (signal.spatial()) {
        Double fact = spatialFacts.get(signal.comparator());
        if (fact == null || !Double.isFinite(fact) || fact < 0)
          missing.add(signal.property() + ":" + signal.comparator());
        value = fact == null || !Double.isFinite(fact) || fact < 0 ? 0
            : signal.comparator().equals("DISTANCE") ? Math.max(0, 1 - fact / signal.tolerance()) : Math.min(1, fact);
      } else {
        if (!comparable(incoming.get(signal.property()), existing.get(signal.property()), signal))
          missing.add(signal.property() + ":" + signal.comparator());
        value = similarity(incoming.get(signal.property()), existing.get(signal.property()), signal);
        nonSpatial |= value > 0;
      }
      evidence.put(signal.property() + ":" + signal.comparator(), value);
      total += signal.weight() * value;
    }
    // Missing evidence contributes zero; never re-normalize weights over available fields.
    return new Candidate(id, Math.max(0, Math.min(1, total)), Collections.unmodifiableMap(evidence), nonSpatial, List.copyOf(missing));
  }

  public static Outcome decide(List<Candidate> candidates, Policy policy, boolean tooBroad) {
    var sorted = candidates.stream().sorted(Comparator.comparingDouble(Candidate::score).reversed().thenComparing(Candidate::id)).toList();
    if (tooBroad || candidates.size() > policy.maxCandidates()) return new Outcome("REVIEW_REQUIRED", null, "RESOLUTION_TOO_BROAD", sorted);
    if (sorted.isEmpty()) return new Outcome("NEW_OBJECT", null, "NO_CANDIDATES", sorted);
    Candidate best = sorted.getFirst();
    if (best.score() < policy.reviewThreshold()) {
      // Absence of evidence is not evidence that the object is different. Keep zero
      // weights in the score, but do not create a duplicate because a field is missing.
      if (sorted.stream().anyMatch(candidate -> !candidate.missingSignals().isEmpty()))
        return new Outcome("REVIEW_REQUIRED", null, "IDENTITY_EVIDENCE_INCOMPLETE", sorted);
      return new Outcome("NEW_OBJECT", null, "BELOW_REVIEW_THRESHOLD", sorted);
    }
    double margin = sorted.size() < 2 ? 1 : best.score() - sorted.get(1).score();
    if (best.score() >= policy.highThreshold() && margin >= policy.minimumMargin()
        && (best.nonSpatialEvidence() || policy.allowSpatialIdentity()))
      return new Outcome("MATCH", best.id(), "HIGH_CONFIDENCE_UNIQUE", sorted);
    return new Outcome("REVIEW_REQUIRED", null, "AMBIGUOUS_OR_POLICY_REVIEW", sorted);
  }

  private static double similarity(Object a, Object b, Signal rule) {
    if (a == null || b == null || a instanceof Map || b instanceof Map || a instanceof Collection || b instanceof Collection) return 0;
    if (rule.comparator().equals("NUMBER")) {
      if (!(a instanceof Number x) || !(b instanceof Number y) || !Double.isFinite(x.doubleValue()) || !Double.isFinite(y.doubleValue())) return 0;
      return Math.max(0, 1 - Math.abs(x.doubleValue() - y.doubleValue()) / rule.tolerance());
    }
    String x = normalize(a), y = normalize(b);
    if (x.isEmpty() || y.isEmpty()) return 0;
    if (rule.comparator().equals("EXACT")) return x.equals(y) ? 1 : 0;
    if (x.length() > 512 || y.length() > 512) throw new IllegalArgumentException("UDP_IDENTITY_TEXT_LIMIT");
    int[] previous = new int[y.length() + 1]; for (int j = 0; j <= y.length(); j++) previous[j] = j;
    for (int i = 1; i <= x.length(); i++) {
      int[] next = new int[y.length() + 1]; next[0] = i;
      for (int j = 1; j <= y.length(); j++) next[j] = Math.min(Math.min(previous[j] + 1, next[j - 1] + 1), previous[j - 1] + (x.charAt(i - 1) == y.charAt(j - 1) ? 0 : 1));
      previous = next;
    }
    return 1 - (double) previous[y.length()] / Math.max(x.length(), y.length());
  }
  private static boolean comparable(Object a, Object b, Signal rule) {
    if (a == null || b == null || a instanceof Map || b instanceof Map || a instanceof Collection || b instanceof Collection) return false;
    if (rule.comparator().equals("NUMBER"))
      return a instanceof Number x && b instanceof Number y && Double.isFinite(x.doubleValue()) && Double.isFinite(y.doubleValue());
    return !normalize(a).isEmpty() && !normalize(b).isEmpty();
  }
  private static String normalize(Object value) { return Normalizer.normalize(String.valueOf(value), Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); }
  private static boolean unit(double value) { return Double.isFinite(value) && value >= 0 && value <= 1; }
  private static IllegalArgumentException invalid() { return new IllegalArgumentException("UDP_IDENTITY_POLICY_INVALID"); }
}
