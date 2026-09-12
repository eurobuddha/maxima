const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// Exercise the shipped script in a VM with just its browser boundary stubbed. No accounts,
// sockets, production test hooks or duplicate implementation of the async state machine.
const source = fs.readFileSync(process.env.PARLONS_PANEL_SOURCE || path.join(__dirname, '../../main/resources/panel/app.js'), 'utf8');
function element() {
  const classes = new Set(), attrs = {}, handlers = {};
  return { attrs, handlers, disabled: false, parentElement: {}, textContent: '', innerHTML: '',
    classList: { contains: k => classes.has(k), toggle(k, on) { on ? classes.add(k) : classes.delete(k); } },
    addEventListener(k, f) { handlers[k] = f; }, setAttribute(k,v) { attrs[k] = v; }, removeAttribute(k) { delete attrs[k]; },
    querySelectorAll() { return []; }, style: {}, scrollHeight: 100, scrollTop: 0, clientHeight: 100 };
}
function harness(fetch) {
  const nodes = new Map();
  const node = id => { if (!nodes.has(id)) nodes.set(id, element()); return nodes.get(id); };
  const renders = { page: 0, list: 0, messages: 0 };
  const context = vm.createContext({ fetch, document: { getElementById: node, querySelectorAll: () => [], addEventListener() {}, documentElement: element() },
    window: { icon: () => '', addEventListener() {} }, localStorage: { getItem: () => null },
    setTimeout() {}, clearTimeout() {}, Uint8Array, btoa: s => Buffer.from(s, 'binary').toString('base64'), renders });
  const expose = `globalThis.panel = { S, api, chatPhotos, refreshPill, loadOlder, reloadOpenTail, loadSummaries, sendFile,
    wireSwitch: typeof wireSwitch === 'function' ? wireSwitch : null,
    select(peer, group = false) { ++openSeq; S.open = peer; S.openIsGroup = group; S.msgs = [{id: peer, time: 100}]; olderBusy = false; olderDone = false; },
    get busy() { return olderBusy; }, get done() { return olderDone; } };
    renderChats = () => { renders.page++; }; renderChatsList = () => { renders.list++; }; renderMsgs = () => { renders.messages++; };`;
  assert.match(source, /  boot\(\);\s*\}\)\(\);\s*$/);
  vm.runInContext(source.replace(/  boot\(\);\s*\}\)\(\);\s*$/, expose + '\n})();'), context);
  return { p: context.panel, node, renders };
}
const reply = (body, status = 200) => ({ok: status < 400, status, json: async () => body});
function deferred() { let resolve; const promise = new Promise(r => resolve = r); return {promise, resolve}; }

test('a failed status check cannot report a healthy connection', async () => {
  const h = harness(async () => { throw new Error('offline'); }); await h.p.refreshPill();
  assert.equal(h.node('state').textContent, 'offline'); assert.equal(h.node('state').parentElement.className, 'hpill bad');
});
test('expired session stays signed out and unsuccessful HTTP replies reject', async () => {
  const h = harness(async () => reply({}, 401)); await h.p.refreshPill();
  assert.equal(h.node('state').textContent, 'signed out');
  const bad = harness(async () => reply({error: 'unavailable'}, 503));
  await assert.rejects(bad.p.api('settings.get'), /unavailable/);
});
test('summary refresh updates the list without replacing the active search field', async () => {
  const h = harness(async () => reply({summaries: []})); h.p.S.search = 'unsubmitted search';
  await h.p.loadSummaries(); assert.equal(h.renders.page, 0); assert.equal(h.renders.list, 1); assert.equal(h.p.S.search, 'unsubmitted search');
});
test('late history cannot mix messages into another conversation', async () => {
  const d = deferred(), h = harness(() => d.promise); h.p.select('first');
  const old = h.p.loadOlder('first'); h.p.select('second');
  d.resolve(reply({messages: [{id: 'old-first', time: 1}]})); await old;
  assert.deepEqual(Array.from(h.p.S.msgs, x => x.id), ['second']); assert.equal(h.renders.messages, 0);
});
test('late empty history cannot finish or unlock a newer history request', async () => {
  const first = deferred(), second = deferred(); let calls = 0;
  const h = harness(() => (++calls === 1 ? first : second).promise);
  h.p.select('first'); const old = h.p.loadOlder('first');
  h.p.select('second'); const current = h.p.loadOlder('second');
  first.resolve(reply({messages: []})); await old;
  assert.equal(h.p.busy, true); assert.equal(h.p.done, false);
  second.resolve(reply({messages: []})); await current;
  assert.equal(h.p.busy, false); assert.equal(h.p.done, true);
});
test('late send refresh cannot leak the previous conversation into a reopened chat', async () => {
  const d = deferred(), h = harness(() => d.promise); h.p.select('first');
  const pending = h.p.reloadOpenTail('first'); h.p.select('second'); h.p.select('first');
  d.resolve(reply({messages: [{id: 'stale', time: 1}]})); await pending;
  assert.deepEqual(Array.from(h.p.S.msgs, x => x.id), ['first']); assert.equal(h.renders.messages, 0);
});
test('file upload retains its original recipient and group flag after navigation', async () => {
  const d = deferred(), calls = [], h = harness(async (url, options) => { calls.push(JSON.parse(options.body)); return reply({ok: true}); });
  h.p.select('group', true);
  const upload = h.p.sendFile({type: 'text/plain', name: 'hello.txt', arrayBuffer: () => d.promise}, false);
  h.p.select('person', false); d.resolve(new Uint8Array([65]).buffer); await upload;
  assert.equal(calls.length, 1); assert.equal(calls[0].peer, 'group'); assert.equal(calls[0].group, true);
  assert.deepEqual(Array.from(h.p.S.msgs, x => x.id), ['person']); assert.equal(h.renders.messages, 0);
});
test('switch saves once, reflects success after the event ends and preserves value on failure', async () => {
  const h = harness(), button = element(), d = deferred(); let calls = 0;
  h.p.wireSwitch(button, 'Read receipts', () => { calls++; return d.promise; }, () => 'Saved');
  const save = button.handlers.click(); await button.handlers.click(); assert.equal(calls, 1); assert.equal(button.disabled, true);
  d.resolve(); await save; assert.equal(button.attrs['aria-checked'], 'true'); assert.equal(button.disabled, false);
  h.p.wireSwitch(button, 'Read receipts', async () => { throw new Error('No connection'); }, () => 'Saved');
  await button.handlers.click(); assert.equal(button.attrs['aria-checked'], 'true'); assert.equal(h.node('toast').textContent, 'No connection');
});

test('photo gallery excludes other media, preserves chronological order and deduplicates history', () => {
  const h = harness(async () => reply({}));
  const photo = (id, time) => ({id, time, body: '\u0001m\u0001image/jpeg\u0001data:image/jpeg;base64,AA==\u0001caption'});
  const rows = [photo('late', 3), {id: 'text', time: 1, body: 'text'}, photo('early', 1), photo('late', 3),
    {id: 'audio', time: 2, body: '\u0001m\u0001audio/ogg\u0001data:audio/ogg;base64,AA==\u0001'}];
  assert.deepEqual(Array.from(h.p.chatPhotos(rows), p => p.id), ['early', 'late']);
  assert.equal(rows.length, 5);
});
