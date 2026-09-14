#!/usr/bin/env python3
import pathlib
import sys

rendered = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
required = {
    "immutable image digest": "@sha256:",
    "startup probe": "startupProbe:",
    "liveness probe": "livenessProbe:",
    "readiness probe": "readinessProbe:",
    "grace period": "terminationGracePeriodSeconds:",
    "pre-stop drain": "preStop:",
    "non-root": "runAsNonRoot: true",
    "read-only filesystem": "readOnlyRootFilesystem: true",
    "network policy": "kind: NetworkPolicy",
    "disruption budget": "kind: PodDisruptionBudget",
}
missing = [name for name, marker in required.items() if marker not in rendered]
if missing:
    raise SystemExit("deployment contract missing: " + ", ".join(missing))

application = pathlib.Path("src/main/resources/application.yml").read_text(encoding="utf-8")
dockerfile = pathlib.Path("Dockerfile").read_text(encoding="utf-8")
runtime_required = {
    "Spring graceful shutdown": (application, "shutdown: graceful"),
    "bounded shutdown phase": (application, "timeout-per-shutdown-phase:"),
    "health probes enabled": (application, "probes.enabled: true"),
    "container SIGTERM": (dockerfile, "STOPSIGNAL SIGTERM"),
}
runtime_missing = [name for name, (content, marker) in runtime_required.items() if marker not in content]
if runtime_missing:
    raise SystemExit("runtime shutdown contract missing: " + ", ".join(runtime_missing))

print("deployment contract verified: " + ", ".join(required) + ", " + ", ".join(runtime_required))
