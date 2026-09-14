# PET related search and MCP capability manifest

Normative source: UDP PET v1.3 sections 41, 48, 98, 100 and acceptance
criteria UDP-A24, UDP-A28, UDP-A37 and UDP-A38.

## Typed related search

`POST /api/udp/v1/objects/related-search` accepts only the logical relationship
pattern required to correlate known classes:

- anchor Urban Object identifier and canonical type;
- one governed `relationIri`;
- semantic direction (`INBOUND` or `OUTBOUND`);
- one or more canonical target types;
- bounded result limit.

The request contract contains no SQL, JPQL, CTE, Cypher, SPARQL, query-language,
join-order, index-hint or physical-plan field. `RelatedSearchService` translates
the DTO into a validated `LogicalQueryPlan` and selects one of two fixed,
parameterized relationship joins backed by the existing inbound/outbound
indexes. Authorization is checked before translation; anchor tenant/type,
relationship access label, target tenant/type and the cumulative correlation
budget are enforced server-side.

## Capability manifest publication gate

`mcp-capability-manifest-v1.json` is the canonical description overlay for all
MCP-eligible capabilities published by `openapi-serving.yaml`. Every row contains
the seven mandatory MCP-MANIFEST-01 fields:

- `purpose`
- `useWhen`
- `doNotUseWhen`
- `preferredAlternatives`
- `operationalLimits`
- `resultSemantics`
- `securityNotes`

`verify-mcp-capability-manifest.py` fails CI when an eligible OpenAPI capability
has no manifest row, a manifest row is unpublished, a mandatory field is empty,
or the `urban.graph.traverse` description does not prohibit cross-domain use and
name both specialist alternatives. Guidance never replaces the authoritative
Router; mismatch remediation and retry-loop enforcement remain separate PET
increments.
