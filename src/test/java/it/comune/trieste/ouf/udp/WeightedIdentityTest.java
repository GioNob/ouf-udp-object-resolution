package it.comune.trieste.ouf.udp;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WeightedIdentityTest {
  private final WeightedIdentity.Policy policy = new WeightedIdentity.Policy(List.of(
      new WeightedIdentity.Signal("name", "TEXT", .4, null),
      new WeightedIdentity.Signal("address", "TEXT", .3, null),
      new WeightedIdentity.Signal("geometry", "DISTANCE", .3, 20.0)), List.of("district"), 100.0, 10, .85, .5, .1, false);

  @Test void identityIsIndependentOfDomainAndCandidateOrder() {
    for (String type : List.of("Dehor", "Camera", "Cabinet", "Tree")) {
      var input = Map.<String,Object>of("name", type + " A", "address", "Via Roma 10");
      var match = WeightedIdentity.score(UUID.randomUUID(), input, input, Map.of("DISTANCE", 0.0), policy);
      var other = WeightedIdentity.score(UUID.randomUUID(), input, Map.of("name", "Other", "address", "Via Milano 90"), Map.of("DISTANCE", 30.0), policy);
      assertEquals(match.id(), WeightedIdentity.decide(List.of(other, match), policy, false).target());
      assertEquals(match.id(), WeightedIdentity.decide(List.of(match, other), policy, false).target());
    }
  }
  @Test void equalHighScoresRequireHumanReview() {
    var values = Map.<String,Object>of("name", "A", "address", "B");
    var first = WeightedIdentity.score(UUID.randomUUID(), values, values, Map.of("DISTANCE", 0.0), policy);
    var second = WeightedIdentity.score(UUID.randomUUID(), values, values, Map.of("DISTANCE", 0.0), policy);
    assertEquals("REVIEW_REQUIRED", WeightedIdentity.decide(List.of(first, second), policy, false).outcome());
  }
  @Test void missingDataNeverInflatesConfidence() {
    var candidate = WeightedIdentity.score(UUID.randomUUID(), Map.of("name", "A"), Map.of("name", "A"), Map.of(), policy);
    assertEquals(.4, candidate.score(), 1e-9);
    assertEquals(List.of("address:TEXT", "geometry:DISTANCE"), candidate.missingSignals());
    assertEquals("IDENTITY_EVIDENCE_INCOMPLETE", WeightedIdentity.decide(List.of(candidate), policy, false).reason());
  }
  @Test void knownDifferencesCanCreateANewObjectButMissingOrInvalidValuesCannot() {
    var numeric = new WeightedIdentity.Policy(List.of(new WeightedIdentity.Signal("size", "NUMBER", 1, 10.0)), List.of("district"), null, 10, .85, .5, .1, false);
    for (Object value : List.of("unknown", Double.NaN, List.of(10), Map.of("value", 10))) {
      var candidate = WeightedIdentity.score(UUID.randomUUID(), Map.of("size", value), Map.of("size", 10), Map.of(), numeric);
      assertEquals(0, candidate.score());
      assertEquals("REVIEW_REQUIRED", WeightedIdentity.decide(List.of(candidate), numeric, false).outcome());
    }
    var different = WeightedIdentity.score(UUID.randomUUID(), Map.of("size", 100), Map.of("size", 10), Map.of(), numeric);
    assertTrue(different.missingSignals().isEmpty());
    assertEquals("NEW_OBJECT", WeightedIdentity.decide(List.of(different), numeric, false).outcome());
    var missing = WeightedIdentity.score(UUID.randomUUID(), Map.of("size", 100), Map.of(), Map.of(), numeric);
    assertEquals("REVIEW_REQUIRED", WeightedIdentity.decide(List.of(different, missing), numeric, false).outcome());
    assertEquals("NEW_OBJECT", WeightedIdentity.decide(List.of(), numeric, false).outcome());
  }
  @Test void numericToleranceAndHighThresholdHaveExplicitBoundaries() {
    var numeric = new WeightedIdentity.Policy(List.of(new WeightedIdentity.Signal("size", "NUMBER", 1, 10.0)), List.of("district"), null, 10, .85, .5, .1, false);
    for (double value : List.of(101.49, 101.5, 101.51, 105.0, 105.01)) {
      var candidate = WeightedIdentity.score(UUID.randomUUID(), Map.of("size", value), Map.of("size", 100), Map.of(), numeric);
      String expected = value <= 101.5 ? "MATCH" : value <= 105 ? "REVIEW_REQUIRED" : "NEW_OBJECT";
      assertEquals(expected, WeightedIdentity.decide(List.of(candidate), numeric, false).outcome(), "value=" + value);
    }
  }
  @Test void proximityAloneDoesNotEstablishIdentity() {
    var spatial = new WeightedIdentity.Policy(List.of(new WeightedIdentity.Signal("geometry", "DISTANCE", 1, 20.0)), List.of(), 100.0, 10, .9, .5, .1, false);
    var candidate = WeightedIdentity.score(UUID.randomUUID(), Map.of(), Map.of(), Map.of("DISTANCE", 0.0), spatial);
    assertEquals("REVIEW_REQUIRED", WeightedIdentity.decide(List.of(candidate), spatial, false).outcome());
  }
  @Test void candidateOverflowCannotChooseFromATruncatedSet() {
    assertEquals("RESOLUTION_TOO_BROAD", WeightedIdentity.decide(List.of(), policy, true).reason());
  }
  @Test void invalidConfigurationAndOversizedTextAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> new WeightedIdentity.Signal("x", "NUMBER", 1, Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> new WeightedIdentity.Policy(policy.signals(), List.of(), null, 10, .9, .5, .1, false));
    assertThrows(IllegalArgumentException.class, () -> WeightedIdentity.score(UUID.randomUUID(), Map.of("name", "a".repeat(513)), Map.of("name", "b"), Map.of(), policy));
  }
}
