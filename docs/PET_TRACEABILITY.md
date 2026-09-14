# UDP PET traceability

This repository is audited against
`OUF_PET_Data_Lake_UDP_Urban_Object_Registry_v1_3_Development_Ready.docx`
from `OUF_Reality_Baseline_Package_v1_5.zip`.

The PET is the normative source. A successful CI run proves only the checks that
were actually executed; it does not imply complete PET acceptance.

## Current conformance baseline

Audit baseline: commit `2c3dcb25aa7a1270b7eadba64993b685e465e6ca`, GitHub
Actions run `34813883857`.

| Status | Requirements |
|---|---:|
| `VERIFIED` | 44 |
| `VERIFIED-LAB` | 2 |
| `PARTIAL` | 18 |
| `OPEN` | 3 |
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

1. Progressive-pruning evidence and analytical-capability rejection.
2. Multi-Pod governor, restart, bulkhead, cleanup/bloat and adversarial evidence.
3. N/N+1 migration compatibility and cross-module fixtures against deployed registries.
4. Production capacity/SLO and approved RPO/RTO acceptance.
