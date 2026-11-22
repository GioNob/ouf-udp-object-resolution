// External CI peers only. UDP validates the bearer, grants and publication checksums itself.
import {createHash, generateKeyPairSync, randomBytes, randomUUID, sign} from 'node:crypto';
import {appendFileSync, existsSync, unlinkSync, writeFileSync} from 'node:fs';
import {createServer} from 'node:http';
import {join} from 'node:path';

const directory = process.env.FIXTURE_DIR;
const issuer = 'http://127.0.0.1:18083';
const tenant = 'tenant-ci';
const subject = 'ci-ingestion';
const capabilities = ['datalake.write', 'udp.candidate.write', 'urban.object.read', 'urban.object.history.read'];
const now = Math.floor(Date.now() / 1000);
const {privateKey, publicKey} = generateKeyPairSync('rsa', {modulusLength: 2048});
const jwk = {...publicKey.export({format: 'jwk'}), kid: randomUUID(), alg: 'RS256', use: 'sig'};
const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
const claims = {iss: issuer, aud: 'udp-ci', iat: now, exp: now + 3600, sub: subject,
  client_id: subject, tenant_id: tenant, ouf_actor_type: 'SERVICE', acr: '1', scope: capabilities.join(' ')};
const signed = encode({alg: 'RS256', kid: jwk.kid}) + '.' + encode(claims);
writeFileSync(join(directory, 'bearer'), signed + '.' + sign('RSA-SHA256', Buffer.from(signed), privateKey).toString('base64url'), {mode: 0o600});

const policy = {bundleId: 'return-fixture', version: 1, publishedAt: now,
  capabilities: capabilities.map(capabilityId => ({capabilityId, operation: 'EXECUTE', requiredScope: capabilityId, allowedActors: ['SERVICE']})),
  grants: capabilities.map(capabilityId => ({grantId: 'grant:' + capabilityId, capabilityId,
    tenantId: tenant, subjectId: subject, validFrom: now - 5, validUntil: now + 3600}))};
writeFileSync(join(directory, 'policy.json'), JSON.stringify(policy), {mode: 0o444});
const gatewayToken = randomBytes(32).toString('hex');
writeFileSync(join(directory, 'gateway-token'), gatewayToken, {mode: 0o444});

const sourceType = {sourceId: 'worksites', typeCode: 'WORKSITE'};
const binding = {semanticId: 'ci:Worksite', semanticVersion: '1', revisionId: randomUUID(), publicationSetId: randomUUID()};
const execution = {sourceSchemaRef: 'schema://ci/1', semanticPublicationSetRef: binding.publicationSetId,
  adapterProfileRef: 'adapter://ci/1', mappingRefs: ['mapping://ci/1']};
const resolution = {strategyId: 'ATTRIBUTE_EXACT', strategyVersion: '1', policyRef: 'policy://ci/identity',
  canonicalType: binding.semanticId, canonicalKeyProperty: 'code', matchProperty: 'code'};
const materialization = {policyRef: 'policy://ci/authority', checkpointInterval: 1, bitemporalProperties: [],
  properties: [{sourceField: 'name', propertyIri: 'name', datatype: 'string', accessLabel: 'OPEN', authorityOrder: [sourceType.sourceId]}]};
const bundle = {bundleId: 'ci-publication', bundleVersion: '1', semanticReferenceBindings: [binding],
  semanticMapping: {sourceType, targetClasses: [{classIri: binding.semanticId}], propertyMappings: [{targetPropertyIri: 'name'}]},
  dataAccessPolicies: [{scope: 'PROPERTY', target: 'name', label: 'OPEN'}],
  extractionProfile: {runtime: {execution, udp: {resolution, materialization}}}};
function sorted(value) {
  if (Array.isArray(value)) return value.map(sorted);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key, sorted(value[key])]));
  return value;
}
bundle.checksum = 'sha256:' + createHash('sha256').update(JSON.stringify(sorted(bundle))).digest('hex');
const bundleRef = [bundle.bundleId, bundle.bundleVersion, bundle.checksum].join(':');
writeFileSync(join(directory, 'contract-refs.json'), JSON.stringify({...execution, bundleRef}));

const tupleKeys = ['semanticId', 'revisionId', 'publicationSetId'];
const compact = {semantic_id: binding.semanticId, semantic_version: binding.semanticVersion,
  status: 'ACTIVE', artifact_type: 'CLASS', revision_id: binding.revisionId,
  publication_set_id: binding.publicationSetId, publication_checksum: bundle.checksum, content_hash: bundle.checksum};
createServer(async (request, response) => {
  const url = new URL(request.url, issuer);
  let body;
  if (url.pathname === '/.well-known/openid-configuration') {
    body = {issuer, jwks_uri: issuer + '/keys', authorization_endpoint: issuer + '/authorize', token_endpoint: issuer + '/token',
      response_types_supported: ['code'], subject_types_supported: ['public'], id_token_signing_alg_values_supported: ['RS256']};
  } else if (url.pathname === '/keys') {
    body = {keys: [jwk]};
  } else if (request.headers.authorization !== 'Bearer ' + gatewayToken) {
    response.writeHead(401).end(); return;
  } else if (url.pathname === '/api/onboarding/v1/runtime/publications/resolve' && url.searchParams.get('bundleRef') === bundleRef) {
    const outage = join(directory, 'unavailable-once');
    if (existsSync(outage)) {
      unlinkSync(outage);
      response.writeHead(429, {'Retry-After': '1'}).end();
      return;
    }
    body = {tenantId: tenant, sourceId: sourceType.sourceId, checksum: bundle.checksum, bundle};
  } else if (request.method === 'POST' && url.pathname === '/api/semantic/v1/references:resolve-batch') {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const data = Buffer.concat(chunks);
    if (data.length > 262144) { response.writeHead(413).end(); return; }
    let references;
    try { ({references} = JSON.parse(data)); } catch { response.writeHead(400).end(); return; }
    if (!Array.isArray(references) || references.length !== 1 ||
        !tupleKeys.every(key => references[0]?.[key] === binding[key])) {
      response.writeHead(400).end(); return;
    }
    body = {contract: 'ouf.semantic.reference-batch.v1', registryVersion: 'ci-set-1',
      results: [{index: 0, ...references[0], outcome: 'RESOLVED', resolved: compact}]};
    appendFileSync(join(directory, 'reference-methods'), 'POST\n');
  } else if (request.method === 'GET' && url.pathname === '/api/semantic/v1/references:resolve' &&
      tupleKeys.every(key => url.searchParams.get(key) === binding[key])) {
    body = compact;
    appendFileSync(join(directory, 'reference-methods'), 'GET\n');
  } else {
    response.writeHead(404).end(); return;
  }
  response.writeHead(200, {'Content-Type': 'application/json'}).end(JSON.stringify(body));
}).listen(18083, '127.0.0.1');
