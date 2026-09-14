# Deployment and supply-chain baseline

This repository produces a CycloneDX JSON SBOM during the Maven package phase and runs a dedicated CI job that scans the deployable container image with Trivy. The gate rejects fixed HIGH or CRITICAL vulnerabilities. JSON scan results, the SBOM, the rendered deployment and SHA-256 checksums are retained as workflow evidence.

The dependency baseline stays on the Spring Boot 3.5 line required by this module while overriding security patch levels for Spring Framework, Tomcat, Jackson, Micrometer, Netty and PostgreSQL JDBC. The runtime uses the smaller Temurin Alpine image so unrelated operating-system helper binaries are not shipped with the application.

The Helm chart in `deploy/helm/ouf-udp` follows the OUF starter-kit boundary: ClusterIP-only service, gateway-scoped ingress, platform-scoped egress, external Secret and ConfigMap bindings, non-root execution, immutable image digest, PDB and explicit resource bounds. Values contain no credentials.

Spring Boot exposes dedicated liveness and readiness groups. Kubernetes delays traffic until readiness succeeds, sends a pre-stop drain delay and then SIGTERM, while Spring performs a bounded graceful shutdown. The pod grace period intentionally exceeds the application shutdown timeout plus the pre-stop delay.

This is executable CI and deployment-contract evidence. It does not certify a target cluster, registry, IAM binding, secrets provider, production vulnerability exception process, capacity, RPO or RTO; those remain representative-environment acceptance responsibilities.
