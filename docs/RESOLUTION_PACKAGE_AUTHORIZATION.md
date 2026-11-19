# Resolution package owner authorization

The browser package GET, delegated package POST and package confirmation authorize
their specific capability on the same immutable owner policy snapshot as request
authentication. The principal must be HUMAN; tenant, subject, scope, grant validity,
explicit DENY and policy freshness remain enforced by the shared authorization SDK.

The previous servlet adapter evaluated a generic resource with no attributes.
A grant constrained by `allowedDataLabels` or `allowedDetailLevels` therefore did
not apply, even when it allowed every label and detail level. This reproduced the
target's `Owner authorization denied` with policy version 40. Reading grant metadata
alone does not prove the target UDP runtime loaded that snapshot.

The current package returns every open issue in the authenticated tenant, with
mixed evidence that has no individual classification in this API. It requires
coverage of all five existing governed labels and all four detail levels, evaluated
on `resolution-issues` with server-controlled attributes. This deliberately strict
whole-package profile does not infer an ordering among opaque labels or select a
permissive default. A principal restricted to a subset cannot read or confirm the
whole package. Narrower access requires separately classified and filtered issues.

No grants, SDK evaluator, database migrations, decision semantics or caller-supplied
classification are changed. Confirmation still requires the exact current snapshot,
complete choices and explicit human action. The fix does not choose any identities.

`ResolutionPackageAuthorizationTest` uses the real SDK and the observed constrained
grant shape. It reproduces the empty-attribute denial, exercises all three controller
paths, and checks partial coverage, explicit DENY, subject, scopes, actor type,
forged headers, missing policy and revoked capability.
