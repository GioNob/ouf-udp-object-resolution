# Linked property activation compatibility — 17 October 2026

The installed reconciler rejects a property whose existing datatype is the XML Schema string IRI when a reconciliation rule is added. Accept the exact IRI as a string comparison type without changing the serialized property rule, its datatype, authority, or provenance.

Adds unit coverage for typed rejection, serialization, normalizers and conflicts, plus PostgreSQL coverage for profile pinning, preview and materialization. Existing linked-property and module CI qualify the change; this PR does not deploy code or publish any source profile.

Normative basis: attached Reality Baseline v1.7, Matrix1.7 §1, UDP PET1.3 §§14–19, Onboarding PET1.6 §90. Technical8/8; functional2/6 unchanged.

Only `http://www.w3.org/2001/XMLSchema#string` is added. No generic URI suffix matching, implicit datatype conversion, cardinality inference or source-name branch is introduced. Published semantic IRIs and legacy rule serialization remain intact. Other XML Schema types require separate typed contract qualification.

Activation remains blocked until both application changes are qualified and installed, a complete configuration-preserving candidate is validated, and scheduler effects are explicitly handled. A new MANAGED publication can re-arm a consumed one-shot schedule; missing top-level triggerOnce does not disable that behavior. The deployment preparation and target evidence belong in GioNob/ouf-deploy.
