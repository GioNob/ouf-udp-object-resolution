#!/usr/bin/env python3
"""Keep deployed UDP Flyway checksums and the candidate sequence pinned."""

from pathlib import Path
import re
import zlib


root = Path(__file__).resolve().parents[2] / "src/main/resources/db/migration"
files = list(root.glob("V*__*.sql"))
versions = {}
for path in files:
    match = re.fullmatch(r"V(\d+)__[A-Za-z0-9_]+\.sql", path.name)
    if match is None:
        raise SystemExit("MIGRATION_FILENAME_INVALID")
    version = int(match.group(1))
    if version in versions:
        raise SystemExit(f"MIGRATION_VERSION_REUSED={version}")
    versions[version] = path

if set(versions) != set(range(1, 40)):
    raise SystemExit("MIGRATION_SEQUENCE_INCOMPLETE")

# V22 is pinned to the live image 944c2f5. V23–V26 were also checked
# against the applied flyway_schema_history on 2026-09-29.
deployed = {
    22: ("V22__weighted_identity_evidence.sql", -252379595),
    23: ("V23__geometry_authority_conflict.sql", 561679179),
    24: ("V24__human_geometry_decisions.sql", -835784960),
    25: ("V25__human_property_decisions.sql", -1491487699),
    26: ("V26__geometry_roles_and_validity.sql", -369310849),
    37: ("V37__repeatable_materialization.sql", 1985595747),  # Bridge 7809a0fd
    38: ("V38__lake_copy_and_active_identity_contract.sql", -81279437),  # D-080 ebc5a322
}
for version, (filename, expected) in deployed.items():
    path = versions[version]
    # Flyway's SQL checksum calculates CRC32 across decoded lines without
    # line separators. The checked-in migrations are UTF-8 without a BOM.
    normalized = b"".join(path.read_bytes().splitlines())
    value = zlib.crc32(normalized)
    signed = value if value < 2**31 else value - 2**32
    if path.name != filename or signed != expected:
        raise SystemExit(f"DEPLOYED_MIGRATION_CHANGED={version}")

print("LIVE_MIGRATIONS_V22_TO_V26_AND_V37_V38_IMMUTABLE=PASS LATEST=V39")

