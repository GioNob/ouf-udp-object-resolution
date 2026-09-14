# UDP PET identifier collision notice

## Scope

This notice records an identifier collision found during the implementation
review of the UDP PET v1.3. It is not a modification of the canonical PET.

## Collision

The acceptance matrix inherited from v1.0 assigns:

- `UDP-A21` to concurrency;
- `UDP-A22` to backup and restore.

The v1.2 conformance addendum later assigns the same identifiers to:

- `UDP-A21` to unchanged-observation revision suppression;
- `UDP-A22` to selective `BITEMPORAL_REQUIRED` behavior.

## Repository convention

Until the PET owner publishes a governed correction, repository traceability and
evidence use the following qualified keys:

- `UDP-A21 [v1.0]` and `UDP-A22 [v1.0]`;
- `UDP-A21 [v1.2]` and `UDP-A22 [v1.2]`.

The original `id` is retained separately in the machine-readable matrix. Tests,
issues and evidence must cite the qualified key whenever they refer to one of
the four colliding criteria.

## Proposed governed correction

The PET owner should assign new, globally unique acceptance identifiers to the
two v1.2 addendum entries and publish the mapping through normal document change
control. No implementation may silently guess the future identifiers.
