package it.comune.trieste.ouf.udp;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

record ApiObservation(String kind, String epoch, String rendering, String hash, String operation,
                      long revision, Long generation, OffsetDateTime observedAt, OffsetDateTime snapshotAt) {
  record State(long revision, String retiredBy, Long generation, OffsetDateTime absenceAt, UUID binding) {}
  enum Outcome { APPLIED, DUPLICATE, DUPLICATE_REPUBLISHED, CONFLICT, SUPERSEDED, RETIRED, REACTIVATED, SEEN }

  static ApiObservation parse(Map<String,Object> payload) {
    var obs = HandoffIntakeService.object(payload, "apiObservation");
    var source = HandoffIntakeService.object(payload, "sourceIdentity");
    var change = HandoffIntakeService.object(payload, "changeRepresentation");
    try {
      String id=(String)source.get("sourceObjectId");
      if (id.isEmpty() || id.codePointCount(0,id.length())>256 || id.codePoints().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException();
      long revision = Long.parseLong((String)source.get("sourceRevision"));
      Long generation = obs.get("generation") == null ? null : Long.valueOf(obs.get("generation").toString());
      Object sequence = change.get("eventSequence");
      if (!Objects.equals(Objects.toString(generation,null), Objects.toString(sequence,null)))
        throw new IllegalArgumentException();
      var snapshot = obs.get("snapshotAt") == null ? null : OffsetDateTime.parse((String)obs.get("snapshotAt"));
      var observed = OffsetDateTime.parse((String)source.get("observedAt"));
      if ("ABSENCE".equals(obs.get("kind")) && !observed.isEqual(snapshot)) throw new IllegalArgumentException();
      return new ApiObservation((String)obs.get("kind"), (String)obs.get("epoch"),
          (String)obs.get("renderedSchemaVersion"), ((String)obs.get("itemSha256")).replaceFirst("^sha256:",""),
          (String)payload.get("operation"), revision, generation, observed, snapshot);
    } catch (RuntimeException invalid) {
      throw new FrozenContractValidator.ContractViolation("UDP_CONTRACT_INVALID", List.of("$.apiObservation"));
    }
  }

  Outcome decide(State state, boolean sameKey, boolean conflict, boolean otherRendering) {
    if (conflict) return Outcome.CONFLICT;
    if (sameKey) return Outcome.DUPLICATE;
    boolean delete = operation.equals("TOMBSTONE");
    if (state == null) return delete ? Outcome.RETIRED : Outcome.APPLIED;
    boolean absent = "ABSENCE".equals(state.retiredBy);
    boolean active = state.retiredBy == null;
    if (kind.equals("ABSENCE")) {
      if (generation <= Objects.requireNonNullElse(state.generation, 0L) || revision != state.revision)
        return Outcome.SUPERSEDED;
      return active ? Outcome.RETIRED : Outcome.DUPLICATE;
    }
    if (otherRendering && revision < state.revision) return Outcome.DUPLICATE_REPUBLISHED;
    if (revision < state.revision) return Outcome.SUPERSEDED;
    if (absent && kind.equals("PRESENCE") && generation > state.generation)
      return state.binding == null ? Outcome.APPLIED : Outcome.REACTIVATED;
    if (otherRendering) return Outcome.DUPLICATE_REPUBLISHED;
    if (delete && !active) return Outcome.DUPLICATE;
    if (revision == state.revision) {
      if (active && kind.equals("PRESENCE")) return Outcome.SEEN;
      return Outcome.DUPLICATE;
    }
    if (delete) return Outcome.RETIRED;
    if (active) return Outcome.APPLIED;
    if (absent && (!kind.equals("EVENT") || !observedAt.isAfter(state.absenceAt))) return Outcome.SUPERSEDED;
    return state.binding == null ? Outcome.APPLIED : Outcome.REACTIVATED;
  }
}
