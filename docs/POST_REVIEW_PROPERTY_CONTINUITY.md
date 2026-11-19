# Post-review property continuity

The published loop pins the materialization profile before identity review in the job transaction. The THS package can then preview linked properties; the same job continues through APPROVE or CREATE_NEW, binding, contributions and commit. New integration tests use real PostgreSQL, review packages, identity service and materializer, with published configuration and reference gate explicitly mocked. They also verify a second worker tick does not duplicate observations.

This change is independent of UDP_REFERENCE_INTEGRITY_MISSING: the reference gate precedes profile resolution. It does not restore unavailable references or prove the runtime CSV stage complete. No publication, identity replay, attempt reset or source-specific application logic is introduced. CI qualification and runtime installation are separate milestones.
