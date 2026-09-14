#!/usr/bin/env python3
"""Fail closed when an MCP-eligible UDP capability lacks PET manifest metadata."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "docs" / "mcp-capability-manifest-v1.json"
OPENAPI = ROOT / "docs" / "openapi-serving.yaml"
REQUIRED = {
    "purpose", "useWhen", "doNotUseWhen", "preferredAlternatives",
    "operationalLimits", "resultSemantics", "securityNotes",
}


def fail(message: str) -> None:
    print(f"MCP capability manifest invalid: {message}", file=sys.stderr)
    raise SystemExit(1)


document = json.loads(MANIFEST.read_text(encoding="utf-8"))
if document.get("schemaVersion") != "ouf.mcp-capability-manifest/v1":
    fail("unsupported or missing schemaVersion")

rows = document.get("capabilities")
if not isinstance(rows, list) or not rows:
    fail("capabilities must be a non-empty array")

by_id = {}
for index, row in enumerate(rows, start=1):
    if not isinstance(row, dict) or not isinstance(row.get("id"), str) or not row["id"].strip():
        fail(f"row {index} has no capability id")
    capability = row["id"]
    if capability in by_id:
        fail(f"duplicate capability {capability}")
    missing = REQUIRED - row.keys()
    if missing:
        fail(f"{capability} is missing {sorted(missing)}")
    for field in REQUIRED - {"preferredAlternatives", "operationalLimits"}:
        if not isinstance(row[field], str) or not row[field].strip():
            fail(f"{capability}.{field} must be non-empty text")
    if not isinstance(row["preferredAlternatives"], list) or not all(
        isinstance(value, str) and value.strip() for value in row["preferredAlternatives"]
    ):
        fail(f"{capability}.preferredAlternatives must be an array of capability ids")
    if not isinstance(row["operationalLimits"], dict) or not row["operationalLimits"]:
        fail(f"{capability}.operationalLimits must be a non-empty object")
    by_id[capability] = row

openapi = OPENAPI.read_text(encoding="utf-8")
published = set()
for line_number, line in enumerate(openapi.splitlines(), start=1):
    if "x-ouf-mcp-eligible: true" not in line:
        continue
    match = re.search(r"x-ouf-capability:\s*([^,}\s]+)", line)
    if not match:
        fail(f"MCP-eligible OpenAPI operation on line {line_number} has no capability")
    published.add(match.group(1))

missing_manifest = published - by_id.keys()
unpublished_manifest = by_id.keys() - published
if missing_manifest:
    fail(f"published capabilities lack manifest rows: {sorted(missing_manifest)}")
if unpublished_manifest:
    fail(f"manifest rows are not published in OpenAPI: {sorted(unpublished_manifest)}")

traverse = by_id.get("urban.graph.traverse", {})
required_alternatives = {"urban.object.related_search", "urban.spatial.intersection_search"}
if not required_alternatives.issubset(traverse.get("preferredAlternatives", [])):
    fail("urban.graph.traverse does not name both PET specialist alternatives")
if "cross-domain" not in traverse.get("doNotUseWhen", "").lower():
    fail("urban.graph.traverse does not explicitly prohibit cross-domain use")

print(f"MCP capability manifest valid: {len(by_id)} tool-eligible capabilities")
