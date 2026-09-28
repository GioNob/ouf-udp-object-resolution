package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.comune.trieste.ouf.udp.GovernedIdentityEngine.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class GovernedIdentityEngineTest {
  private final GovernedIdentityEngine engine = new GovernedIdentityEngine();
  private static final String NAME = "https://schema.org/name@set-1";
  private static final String CODE = "https://example.org/id@set-1";

  @Test void sharedSubsetAndFieldOrderDoNotChangeAGovernedMatch() {
    Policy policy = policy(true, true);
    Subject observed = subject(Map.of("display", value(NAME, "  Cinema   Aurora "), "key", value(CODE, "007")));
    UUID target = UUID.randomUUID();
    Candidate same = candidate(target, Map.of("key", value(CODE, "007"),
        "display", value(NAME, "cinema aurora"), "extra", value("other@set-1", "unrelated")));
    Candidate excluded = candidate(UUID.randomUUID(), Map.of("key", value(CODE, "008"),
        "display", value(NAME, "cinema aurora")));
    Decision first = engine.decide(policy, observed, List.of(same, excluded));
    Decision reversed = engine.decide(policy, observed, List.of(excluded, same));
    assertThat(first.outcome()).isEqualTo(Outcome.MATCH);
    assertThat(first.objectId()).isEqualTo(target);
    assertThat(reversed).isEqualTo(first);
    assertThat(first.assessments()).anySatisfy(a -> assertThat(a.evidence())
        .anySatisfy(e -> assertThat(e.kind()).isEqualTo(EvidenceKind.DISAGREE)));
  }

  @Test void equalNonUniqueLabelCannotAuthorizeMergeOrExcludeCompetitor() {
    Policy policy = policy(true, true);
    Candidate one = candidate(UUID.randomUUID(), Map.of("display", value(NAME, "Aurora"), "key", value(CODE, "007")));
    Candidate possible = candidate(UUID.randomUUID(), Map.of("display", value(NAME, "Aurora")));
    Decision decision = engine.decide(policy, subject(Map.of("display", value(NAME, "Aurora"),
        "key", value(CODE, "007"))), List.of(one, possible));
    assertThat(decision.outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
    assertThat(decision.assessments()).anySatisfy(a -> assertThat(a.evidence())
        .anySatisfy(e -> assertThat(e.kind()).isEqualTo(EvidenceKind.MISSING)));
  }

  @Test void mismatchOfSemanticPublicationCannotCountAsEquality() {
    Decision decision = engine.decide(policy(false, true), subject(Map.of("key", value(CODE, "007"))),
        List.of(candidate(UUID.randomUUID(), Map.of("key", value("https://example.org/id@set-2", "007")))));
    assertThat(decision.outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
  }

  @Test void unresolvedPropertyConflictRequiresHumanEvenWithSufficientKey() {
    Decision decision = engine.decide(policy(true, true),
        subject(Map.of("key", value(CODE, "007"), "display", value(NAME, "Aurora"))),
        List.of(candidate(UUID.randomUUID(), Map.of("key", value(CODE, "007"),
            "display", value(NAME, "Different label")))));
    assertThat(decision.outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
  }

  @Test void boundedCandidatesAndSourceScopedCreationAreExplicit() {
    Subject observation = subject(Map.of("key", value(CODE, "007")));
    assertThat(engine.decide(policy(false, true), observation, List.of()).outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
    assertThat(engine.decide(policy(true, true), observation, List.of()).outcome()).isEqualTo(Outcome.NEW_OBJECT);
    List<Candidate> broad = new ArrayList<>();
    for (int i = 0; i < 11; i++) broad.add(candidate(UUID.randomUUID(), Map.of()));
    assertThat(engine.decide(policy(true, true), observation, broad).outcome())
        .isEqualTo(Outcome.RESOLUTION_TOO_BROAD);
    assertThatThrownBy(() -> engine.decide(policy(true, true),
        new Subject("other-tenant", "Cinema", "file-source", Map.of()), List.of()))
        .hasMessage("UDP_IDENTITY_POLICY_INVALID");
  }

  @Test void sufficientRuleNeedsGovernedUniqueness() {
    assertThatThrownBy(() -> new Policy("policy://id", "1", "default", "Cinema", "file-source", 10,
        true, List.of(new Signal("display", NAME, ComparatorKind.TEXT_V1, false, false)),
        List.of(new SufficientRule("label", Set.of("display")))))
        .hasMessage("UDP_IDENTITY_POLICY_INVALID");
  }

  @Test void preactivationCountsActualRuntimeDecisions() {
    Policy policy = policy(true, true);
    Preflight report = engine.preflight(policy, List.of(
        new Probe(subject(Map.of("key", value(CODE, "007"))), List.of()),
        new Probe(subject(Map.of("key", value(CODE, "008"))),
            List.of(candidate(UUID.randomUUID(), Map.of("key", value(CODE, "008")))))));
    assertThat(report.total()).isEqualTo(2);
    assertThat(report.outcomes()).containsEntry(Outcome.NEW_OBJECT, 1L).containsEntry(Outcome.MATCH, 1L);
    assertThat(report.largestCandidateSet()).isEqualTo(1);
  }

  private static Policy policy(boolean create, boolean unique) {
    return new Policy("policy://cinema", "1", "default", "Cinema", "file-source", 10, create,
        List.of(new Signal("display", NAME, ComparatorKind.TEXT_V1, false, false),
            new Signal("key", CODE, ComparatorKind.CONCEPT, true, unique)),
        List.of(new SufficientRule("governed-key", Set.of("key"))));
  }
  private static Subject subject(Map<String, Value> values) { return new Subject("default", "Cinema", "file-source", values); }
  private static Candidate candidate(UUID id, Map<String, Value> values) { return new Candidate(id, "default", "Cinema", values); }
  private static Value value(String ref, String raw) { return new Value(ref, raw, "fixture://evidence"); }
}
