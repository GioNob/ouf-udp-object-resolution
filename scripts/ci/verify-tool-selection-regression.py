#!/usr/bin/env python3
"""Deterministic cognitive-guidance regression; never a datastore security control."""

from __future__ import annotations

import json
import sys
import unicodedata
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SUITE = ROOT / "docs" / "tool-selection-regression-v1.json"
MANIFEST = ROOT / "docs" / "mcp-capability-manifest-v1.json"
ANALYTICAL_OUTCOME = "QUERY_REQUIRES_ANALYTICAL_CAPABILITY"
CUES = {
    "urban.object.related_search": (
        "stessa strada", "same street", "same relationship anchor", "typed join",
        "non-recursive cross-domain", "associati", "dehor", "worksite", "lightingpole",
    ),
    "urban.spatial.intersection_search": (
        "intersecano", "spatial overlap", "anchor geometry", "stessa area",
        "spatial intersection", "topological",
    ),
    "urban.graph.traverse": (
        "ricorsivamente", "gerarchia", "discendenti", "recursive", "technical network",
        "bounded depth",
    ),
    "urban.graph.neighbors": (
        "adiacenti", "un solo hop", "one-hop", "immediate neighbors", "progressive exploration",
        "pruning",
    ),
    ANALYTICAL_OUTCOME: (
        "trend aggregati", "correlazioni storiche", "anomalie", "tutti i domini",
        "composite analytical", "trend", "aggregation", "full urban dataset",
    ),
}


def fail(message: str) -> None:
    print(f"Tool-selection regression invalid: {message}", file=sys.stderr)
    raise SystemExit(1)


def normalize(value: str) -> str:
    value = unicodedata.normalize("NFKD", value.casefold())
    return " ".join("".join(char for char in value if not unicodedata.combining(char)).split())


def select(prompt: str) -> str:
    normalized = normalize(prompt)
    scores = {
        capability: (
            sum(normalize(cue) in normalized for cue in cues)
            if capability == ANALYTICAL_OUTCOME
            or any(normalize(cue) in manifest_text[capability] for cue in cues)
            else -1
        )
        for capability, cues in CUES.items()
    }
    highest = max(scores.values())
    winners = [capability for capability, score in scores.items() if score == highest]
    if highest == 0 or len(winners) != 1:
        return "UNRESOLVED"
    return winners[0]


suite = json.loads(SUITE.read_text(encoding="utf-8"))
manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
published = {row["id"] for row in manifest["capabilities"]}
manifest_text = {
    row["id"]: normalize(" ".join((
        row["purpose"], row["useWhen"], row["doNotUseWhen"],
        " ".join(row["preferredAlternatives"]), row["resultSemantics"], row["securityNotes"],
    )))
    for row in manifest["capabilities"]
}
cases = suite.get("cases", [])
minimum = suite.get("minimumPassRatio")
if not cases or not isinstance(minimum, (int, float)) or not 0 < minimum <= 1:
    fail("suite cases or minimumPassRatio are invalid")

passed = 0
for case in cases:
    expected = case.get("expectedCapability")
    if expected not in published and expected != ANALYTICAL_OUTCOME:
        fail(f"{case.get('id')} expects an unpublished capability or unsupported outcome {expected}")
    selected = select(case.get("prompt", ""))
    if selected == expected:
        passed += 1
    else:
        print(f"FAIL {case.get('id')}: selected={selected}, expected={expected}", file=sys.stderr)

ratio = passed / len(cases)
if ratio < minimum:
    fail(f"pass ratio {ratio:.2%} is below {minimum:.2%}")
print(f"Tool-selection regression valid: {passed}/{len(cases)} ({ratio:.0%})")
