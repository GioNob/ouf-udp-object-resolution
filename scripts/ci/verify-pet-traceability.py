#!/usr/bin/env python3
"""Validate the machine-readable UDP PET traceability overlay."""

from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MATRIX = ROOT / "docs" / "pet-traceability-v1.3.json"
ALLOWED_STATUSES = {"VERIFIED", "VERIFIED-LAB", "PARTIAL", "OPEN", "EXTERNAL-OPEN"}
EXPECTED_COUNTS = {
    "VERIFIED": 45,
    "VERIFIED-LAB": 2,
    "PARTIAL": 18,
    "OPEN": 2,
    "EXTERNAL-OPEN": 2,
}
COLLIDING_IDS = {"UDP-A21", "UDP-A22"}


def fail(message: str) -> None:
    print(f"PET traceability invalid: {message}", file=sys.stderr)
    raise SystemExit(1)


rows = json.loads(MATRIX.read_text(encoding="utf-8"))
if not isinstance(rows, list) or len(rows) != 69:
    fail("the v1.3 baseline must contain exactly 69 normative rows")

required = {
    "matrix_id", "id", "origin", "area", "requirement", "status",
    "implementation", "evidence",
}
matrix_ids = []
raw_ids = Counter()
statuses = Counter()
for index, row in enumerate(rows, start=1):
    missing = required - row.keys()
    if missing:
        fail(f"row {index} is missing {sorted(missing)}")
    if any(not isinstance(row[field], str) or not row[field].strip() for field in required):
        fail(f"row {index} contains an empty required field")
    if row["status"] not in ALLOWED_STATUSES:
        fail(f"row {index} uses unknown status {row['status']!r}")
    matrix_ids.append(row["matrix_id"])
    raw_ids[row["id"]] += 1
    statuses[row["status"]] += 1

if len(matrix_ids) != len(set(matrix_ids)):
    fail("matrix_id values must be globally unique")

duplicates = {key for key, count in raw_ids.items() if count > 1}
if duplicates != COLLIDING_IDS:
    fail(f"unexpected raw PET identifier collisions: {sorted(duplicates)}")

expected_qualified = {
    "UDP-A21 [v1.0]", "UDP-A22 [v1.0]",
    "UDP-A21 [v1.2]", "UDP-A22 [v1.2]",
}
if not expected_qualified.issubset(matrix_ids):
    fail("A21/A22 collisions are not fully qualified by PET source version")

if dict(statuses) != EXPECTED_COUNTS:
    fail(f"status totals changed without an explicit baseline update: {dict(statuses)}")

print(f"PET traceability valid: {len(rows)} rows; statuses={dict(statuses)}")
