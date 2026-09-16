# UDP PET traceability

This repository is audited against
`OUF_PET_Data_Lake_UDP_Urban_Object_Registry_v1_3_Development_Ready.docx`
from `OUF_Reality_Baseline_Package_v1_7.zip`.

The PET is the normative source. A successful CI run proves only the checks that
were actually executed; it does not imply complete PET acceptance.

## Current conformance baseline

Audit baseline: commit `a18e1c1d1f6add41bb11c975e5f2487f352d1bdb`, GitHub
[Actions run 34886224933](https://github.com/GioNob/ouf-udp-object-resolution/actions/runs/34886224933).
Reality Baseline v1.7 / Cross-Module Matrix v1.7; UDP PET remains v1.3.
The counts below describe the repository classifications, not independent
production acceptance. Snapshot reconciled in R0 on 2026-09-16.

| Status | Requirements |
|---|---:|
| `VERIFIED` | 48 |
| `VERIFIED-LAB` | 3 |
| `PARTIAL` | 16 |
| `OPEN` | 0 |
| `EXTERNAL-OPEN` | 2 |

Overall status: **NOT READY FOR FULL PET ACCEPTANCE**.

The complete requirement-to-code-to-test-to-evidence mapping is maintained in
[`pet-traceability-v1.3.json`](pet-traceability-v1.3.json). CI validates its
structure, status vocabulary, row count and identifier disambiguation.

## PET identifier erratum

The PET reuses `UDP-A21` and `UDP-A22` for different acceptance criteria:

| Matrix key used here | PET location | Meaning |
|---|---|---|
| `UDP-A21 [v1.0]` | §105, acceptance matrix v1.0 | Concurrency |
| `UDP-A22 [v1.0]` | §105, acceptance matrix v1.0 | Backup/restore |
| `UDP-A21 [v1.2]` | §105.1, addendum v1.2 | No revision for unchanged observation |
| `UDP-A22 [v1.2]` | §105.1, addendum v1.2 | Selective `BITEMPORAL_REQUIRED` |

These qualified keys are a traceability overlay only. They do not amend or
renumber the canonical PET. Formal renumbering requires document governance and
cross-module change control.

## Evidence rules

- `VERIFIED`: the complete criterion has direct executable evidence.
- `VERIFIED-LAB`: the complete technical path was exercised in CI/lab, while
  production targets or infrastructure acceptance remain external.
- `PARTIAL`: implementation or tests cover only part of the criterion.
- `OPEN`: required implementation or direct evidence is absent.
- `EXTERNAL-OPEN`: closure requires another OUF module or governed environment.

Composite requirements remain `PARTIAL` if even one normative clause lacks
direct evidence. Presence of fields, tables, configuration or architecture alone
does not count as an end-to-end test.

## Known blocking groups

1. Progressive-pruning and serving isolation under agent load (A29/A35).
2. Global query-budget cleanup/bloat/lock evidence (A42), plus representative
   Kubernetes/SLO acceptance beyond the existing separate-pool replica lab.
3. N/N+1 migration compatibility and cross-module fixtures against deployed registries.
4. Production capacity/SLO and approved RPO/RTO acceptance.

## Resolved items and history

Analytical rejection, delta/bitemporal behavior, retry guards, pool bulkhead
and replica/restart laboratory evidence have subsequent implementation/tests.
Do not reopen them from the old blocking-group text. The JSON retains the
precise criterion-level qualification; see also
[governor resilience](QUERY_BUDGET_GOVERNOR_RESILIENCE.md).

Previous snapshot `2c3dcb25aa7a1270b7eadba64993b685e465e6ca`, run
`34813883857`: 44 VERIFIED, 2 VERIFIED-LAB, 18 PARTIAL, 3 OPEN,
2 EXTERNAL-OPEN. This is historical and superseded by the table above.

The project-wide [R0 gap register](https://github.com/GioNob/ouf-semantic-registry/blob/main/evidence/ouf-pet-gap-register-v1.7.json)
tracks roadmap groups UDP-01/02/03 and dependencies; the 69-row JSON here
remains the detailed UDP mapping. Neither overlay changes canonical PET IDs.
A resolved decision is reopened only on new repository or CI evidence.
