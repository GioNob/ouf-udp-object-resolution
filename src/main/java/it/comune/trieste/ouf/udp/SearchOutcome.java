package it.comune.trieste.ouf.udp;

/** Stable owner outcomes shared by object search and acquisition evidence reads. */
public enum SearchOutcome {
  NO_OBJECTS,
  MATERIALIZATION_PENDING,
  PARTIAL,
  COMPLETE,
  INCOMPLETE;

  static SearchOutcome forPage(boolean hasCursor, boolean incomplete, Integer pollAfterSeconds, boolean empty) {
    if (hasCursor) return PARTIAL;
    if (pollAfterSeconds != null) return MATERIALIZATION_PENDING;
    if (incomplete) return INCOMPLETE;
    return empty ? NO_OBJECTS : COMPLETE;
  }
}
