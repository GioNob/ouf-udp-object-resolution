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
    Decision first = engine.decide(policy, observed, retrieved(policy, same, excluded));
    Decision reversed = engine.decide(policy, observed, retrieved(policy, excluded, same));
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
        "key", value(CODE, "007"))), retrieved(policy, one, possible));
    assertThat(decision.outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
    assertThat(decision.assessments()).anySatisfy(a -> assertThat(a.evidence())
        .anySatisfy(e -> assertThat(e.kind()).isEqualTo(EvidenceKind.MISSING)));
  }

  @Test void mismatchOfSemanticPublicationCannotCountAsEquality() {
    Policy policy = policy(false, true);
    Decision decision = engine.decide(policy, subject(Map.of("key", value(CODE, "007"))),
        retrieved(policy, candidate(UUID.randomUUID(), Map.of("key", value("https://example.org/id@set-2", "007")))));
    assertThat(decision.outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
  }

  @Test void unresolvedPropertyConflictRequiresHumanEvenWithSufficientKey() {
    Policy policy = policy(true, true);
    Decision decision = engine.decide(policy,
        subject(Map.of("key", value(CODE, "007"), "display", value(NAME, "Aurora"))),
        retrieved(policy, candidate(UUID.randomUUID(), Map.of("key", value(CODE, "007"),
            "display", value(NAME, "Different label")))));
    assertThat(decision.outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
  }

  @Test void boundedCandidatesAndSourceScopedCreationAreExplicit() {
    Subject observation = subject(Map.of("key", value(CODE, "007")));
    Policy noCreate = policy(false, true), create = policy(true, true);
    assertThat(engine.decide(noCreate, observation, retrieved(noCreate)).outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
    assertThat(engine.decide(create, observation, retrieved(create)).outcome()).isEqualTo(Outcome.NEW_OBJECT);
    List<Candidate> broad = new ArrayList<>();
    for (int i = 0; i < 11; i++) broad.add(candidate(UUID.randomUUID(), Map.of()));
    assertThat(engine.decide(create, observation, new Candidates(create.ref(), create.version(),
        create.tenantId(), create.canonicalClass(), "index://snapshot-1", true, broad)).outcome())
        .isEqualTo(Outcome.RESOLUTION_TOO_BROAD);
    assertThatThrownBy(() -> engine.decide(create,
        new Subject("other-tenant", "Cinema", "file-source", Map.of()), retrieved(create)))
        .hasMessage("UDP_IDENTITY_POLICY_INVALID");
  }

  @Test void incompleteOrMismatchedCoverageCannotAuthorizeCreationOrMerge() {
    Policy policy = policy(true, true);
    Subject observation = subject(Map.of("key", value(CODE, "007")));
    Candidates incomplete = new Candidates(policy.ref(), policy.version(), policy.tenantId(),
        policy.canonicalClass(), null, false, List.of());
    Decision withheld = engine.decide(policy, observation, incomplete);
    assertThat(withheld.outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
    assertThat(withheld.reason()).isEqualTo("CANDIDATE_COVERAGE_UNVERIFIED");
    Candidate match = candidate(UUID.randomUUID(), Map.of("key", value(CODE, "007")));
    assertThat(engine.decide(policy, observation, new Candidates(policy.ref(), policy.version(),
        policy.tenantId(), policy.canonicalClass(), null, false, List.of(match))).outcome())
        .isEqualTo(Outcome.REVIEW_REQUIRED);
    assertThatThrownBy(() -> engine.decide(policy, observation, new Candidates(policy.ref(), "old",
        policy.tenantId(), policy.canonicalClass(), "index://old", true, List.of())))
        .hasMessage("UDP_IDENTITY_POLICY_INVALID");
    assertThatThrownBy(() -> new Candidates(policy.ref(), policy.version(), policy.tenantId(),
        policy.canonicalClass(), null, true, List.of())).hasMessage("UDP_IDENTITY_POLICY_INVALID");
  }

  @Test void sufficientRuleNeedsGovernedUniqueness() {
    assertThatThrownBy(() -> new Policy("policy://id", "1", "default", "Cinema", "file-source", 10,
        true, List.of(new Signal("display", NAME, ComparatorKind.TEXT_V1, false, false, null)),
        List.of(new SufficientRule("label", Set.of("display"), "assertion://rule/1"))))
        .hasMessage("UDP_IDENTITY_POLICY_INVALID");
  }

  @Test void identityAuthorityRequiresAnExplicitAssertionReference() {
    assertThatThrownBy(() -> new Signal("key", CODE, ComparatorKind.CONCEPT, false, true, null))
        .hasMessage("UDP_IDENTITY_POLICY_INVALID");
    assertThatThrownBy(() -> new Signal("key", CODE, ComparatorKind.CONCEPT, true, false, null))
        .hasMessage("UDP_IDENTITY_POLICY_INVALID");
    assertThatThrownBy(() -> new SufficientRule("rule", Set.of("key"), null))
        .hasMessage("UDP_IDENTITY_POLICY_INVALID");
    Decision match=engine.decide(policy(true, true), subject(Map.of("key", value(CODE, "007"))),
        retrieved(policy(true, true), candidate(UUID.randomUUID(), Map.of("key", value(CODE, "007")))));
    assertThat(match.assessments().getFirst().evidence()).anySatisfy(e ->
        assertThat(e.assertionRef()).isEqualTo("assertion://key-unique/1"));
  }

  @Test void preactivationCountsActualRuntimeDecisions() {
    Policy policy = policy(true, true);
    Preflight report = engine.preflight(policy, List.of(
        new Probe(subject(Map.of("key", value(CODE, "007"))), retrieved(policy)),
        new Probe(subject(Map.of("key", value(CODE, "008"))),
            retrieved(policy, candidate(UUID.randomUUID(), Map.of("key", value(CODE, "008")))))));
    assertThat(report.total()).isEqualTo(2);
    assertThat(report.outcomes()).containsEntry(Outcome.NEW_OBJECT, 1L).containsEntry(Outcome.MATCH, 1L);
    assertThat(report.largestCandidateSet()).isEqualTo(1);
    Candidates incomplete = new Candidates(policy.ref(), policy.version(), policy.tenantId(),
        policy.canonicalClass(), null, false, List.of());
    assertThat(engine.preflight(policy, List.of(new Probe(subject(Map.of()), incomplete))).outcomes())
        .containsEntry(Outcome.REVIEW_REQUIRED, 1L);
  }

  private static Policy policy(boolean create, boolean unique) {
    return new Policy("policy://cinema", "1", "default", "Cinema", "file-source", 10, create,
        List.of(new Signal("display", NAME, ComparatorKind.TEXT_V1, false, false, null),
            new Signal("key", CODE, ComparatorKind.CONCEPT, true, unique, "assertion://key-unique/1")),
        List.of(new SufficientRule("governed-key", Set.of("key"), "assertion://sufficient-key/1")));
  }
  private static Subject subject(Map<String, Value> values) { return new Subject("default", "Cinema", "file-source", values); }
  private static Candidate candidate(UUID id, Map<String, Value> values) { return new Candidate(id, "default", "Cinema", values); }
  private static Value value(String ref, String raw) { return new Value(ref, raw, "fixture://evidence"); }
  private static Candidates retrieved(Policy policy, Candidate... rows) {
    return new Candidates(policy.ref(), policy.version(), policy.tenantId(), policy.canonicalClass(),
        "index://fixture-snapshot", true, List.of(rows));
  }
}
