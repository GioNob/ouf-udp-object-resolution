import assert from 'node:assert/strict';
import {createHash, randomUUID} from 'node:crypto';
import {readFileSync, writeFileSync} from 'node:fs';
import {join} from 'node:path';
import {setTimeout as delay} from 'node:timers/promises';

const [phase, objectId] = process.argv.slice(2);
const directory = process.env.FIXTURE_DIR;
const bearer = readFileSync(join(directory, 'bearer'), 'utf8');
const refs = JSON.parse(readFileSync(join(directory, 'contract-refs.json')));
const template = JSON.parse(readFileSync('src/test/resources/pairwise/ingestion-to-udp-relationship-handoff.json'));
const lake = '/api/internal/v1/lake/objects';
const handoffs = '/api/internal/v1/handoffs';
const file = join(directory, 'receipts.json');
const state = phase === 'seed' ? {} : JSON.parse(readFileSync(file));
async function api(path, data) {
  const response = await fetch('http://127.0.0.1:18080' + path, {
    method: data ? 'POST' : 'GET', redirect: 'error', signal: AbortSignal.timeout(10000),
    headers: {Authorization: 'Bearer ' + bearer, 'Content-Type': 'application/json'},
    body: data ? JSON.stringify(data) : undefined});
  assert.equal(response.status, data ? 201 : 200, phase + ' ' + path + ' HTTP ' + response.status);
  return response.json();
}
async function checkProcessed(entry) {
  const row = await api(handoffs + '/' + entry.payload.handoffId);
  assert.equal(row.content_hash, entry.payload.contentHash);
  assert.equal(row.receipt_ref, entry.receipt.receiptRef);
  assert.ok(row.raw_lake_object_id);
  assert.ok(!row.failure_code, 'Materialization failure: ' + row.failure_code);
  return row.state === 'PROCESSED';
}
async function retry(entry) {
  const expected = {...entry.receipt, duplicate: true};
  // Both callers replay the same durable request, including its idempotency fields.
  const [one, two] = await Promise.all([api(handoffs, entry.payload), api(handoffs, entry.payload)]);
  assert.deepEqual(one, expected);
  assert.deepEqual(two, expected);
  assert.deepEqual(await api(lake, entry.upload), entry.raw);
}
async function materialize(id, value, code = 'ci-row', sourceObjectId = code) {
  const runId = randomUUID();
  const content = Buffer.from(`code,name\n${code},${value}\n`);
  const contentHash = 'sha256:' + createHash('sha256').update(content).digest('hex');
  const upload = {runId, sourceId: template.sourceIdentity.sourceId, typeCode: template.sourceIdentity.typeCode,
    sourceObjectId, zone: 'RAW', contentBase64: content.toString('base64'), contentHash, idempotencyKey: runId};
  const [raw, replay] = await Promise.all([api(lake, upload), api(lake, upload)]);
  assert.equal(raw.contentHash, contentHash);
  assert.equal(raw.durable, true);
  assert.match(raw.objectRef, /^lake:\/\/[0-9a-f-]{36}$/);
  assert.deepEqual(replay, raw);

  const timestamp = new Date().toISOString();
  const payload = {...template, handoffId: id, ingestionRunId: runId, ingestionId: 'ingestion-' + id,
    sourceIdentity: {...template.sourceIdentity, sourceObjectId, observedAt: timestamp},
    canonicalPayload: {code, name: value}, rawObjectRef: raw.objectRef, contractRefs: refs,
    lineageId: 'lineage-' + id, contentHash, acquiredAt: timestamp, correlationId: runId,
    changeRepresentation: {mode: 'FULL_SNAPSHOT', canonicalContentHash: contentHash}};
  const receipt = await api(handoffs, payload);
  assert.deepEqual(receipt, {durable: true, duplicate: false, receiptRef: 'udp://handoffs/' + id});
  const entry = {upload, raw, payload, receipt};
  for (let attempt = 0; attempt < 90; attempt++) {
    if (await checkProcessed(entry)) return entry;
    await delay(500);
  }
  throw Error('Timed out waiting for materialization of ' + id);
}
switch (phase) {
  case 'seed':
    state.seed = await materialize('return-seed', 'A');
    break;
  case 'candidate':
    assert.equal(await checkProcessed(state.seed), true);
    state.changed = await materialize('return-change', 'B');
    state.restored = await materialize('return-restore', 'A');
    assert.equal(state.restored.raw.contentHash, state.seed.raw.contentHash);
    assert.notEqual(state.restored.raw.objectRef, state.seed.raw.objectRef);
    await materialize('return-reused-key', 'original', 'ci-reused');
    await retry(state.changed);
    break;
  case 'return': {
    assert.equal(await checkProcessed(state.changed), true);
    assert.equal(await checkProcessed(state.restored), true);
    const path = '/api/udp/v1/objects/' + objectId;
    const current = await api(path);
    assert.equal(current.properties.name, 'A');
    const history = (await api(path + '/history')).items;
    assert.deepEqual(history.map(row => [row.revision_no, row.properties.name]), [[3,'A'],[2,'B'],[1,'A']]);
    assert.equal(new Set(history.map(row => row.revision_id)).size, 3);
    assert.equal(history[0].revision_id, current.revisionId);
    // Same content as both copies. The bridge must return the candidate's verified UUID.
    await retry(state.restored);
    await retry(state.changed);
    const fresh = await materialize('after-return', 'C');
    await retry(fresh);
    assert.equal((await api(path)).properties.name, 'C');
    // A new source binding forces a scoped lookup among the two equal canonical keys.
    await materialize('after-return-key', 'updated', 'ci-reused', 'ci-new-binding');
    break;
  }
  default: throw Error('Unknown rollback phase');
}
writeFileSync(file, JSON.stringify(state));
console.log('Passed rollback phase ' + phase);
