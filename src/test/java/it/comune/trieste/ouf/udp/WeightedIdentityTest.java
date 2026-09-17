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
