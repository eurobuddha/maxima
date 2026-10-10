const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// Run the shipped controls against an idempotent synthetic account; only browser/API
// boundaries are stubbed, as in panel.test.cjs. No live identities or network access.
function element() {
  const children = new Map();
  return { isConnected: true, hidden: false, disabled: false, textContent: '', items: [],
    querySelector(key) { if (!children.has(key)) children.set(key, element()); return children.get(key); },
    setAttribute() {}, appendChild(child) { this.items.push(child); } };
}
function harness(intercept = () => {}) {
  const uploads = new Map(), calls = [], panels = [], toasts = [];
  let current;
  const context = { window: {}, document: { createElement: element }, Uint8Array, TextDecoder,
    crypto: require('node:crypto').webcrypto, btoa: s => Buffer.from(s, 'binary').toString('base64'),
    atob: s => Buffer.from(s, 'base64').toString('binary'),
    setTimeout(fn, delay) { if (delay <= 500) queueMicrotask(fn); } };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/panel/private-files.js'), 'utf8'), context);
  const ui = context.window.ParlonsFiles.create({
    api: async (method, p) => {
      calls.push({ ...p });
      const before = await intercept(p, 'before'); if (before) return before;
      let u = uploads.get(p.id), reply = { ok: 'true' };
      if (p.action === 'begin') {
        if (!u) { u = { data: Buffer.alloc(0), finishes: 0 }; uploads.set(p.id, u); }
        reply.offset = String(u.data.length);
      } else if (p.action === 'append') {
        assert.ok(u); const offset = Number(p.offset), data = Buffer.from(p.data, 'base64');
        if (offset < u.data.length) assert.deepEqual(u.data.subarray(offset, offset + data.length), data);
        else { assert.equal(offset, u.data.length); u.data = Buffer.concat([u.data, data]); }
        reply.offset = String(u.data.length);
      } else if (p.action === 'finish') { if (!u.finishes) u.finishes++; }
      else if (p.action === 'cancelUpload') uploads.delete(p.id);
      else if (p.action === 'status') reply = { ok: 'true', status: 'Preparing', done: '0', size: '1' };
      else if (p.action === 'list') reply.transfers = '[]';
      const after = await intercept(p, 'after'); return after || reply;
    },
    sheet() { if (current) current.isConnected = false; current = element(); panels.push(current); return current; },
    closeSheet() { if (current) current.isConnected = false; }, esc: s => s,
    toast: (...args) => toasts.push(args)
  });
  return { ui, uploads, calls, panels, toasts, get panel() { return current; } };
}
function file(size = 100 * 1024) {
  const bytes = Buffer.alloc(size); for (let i = 0; i < size; i++) bytes[i] = i % 251;
  const file = new Blob([bytes]); file.name = 'synthetic.bin'; return { file, bytes };
}

test('lost append response retries identical bytes without cancelling the upload', async () => {
  let lost = false;
  const h = harness((p, phase) => { if (p.action === 'append' && p.offset === '49152' && phase === 'after' && !lost) { lost = true; throw Error('lost reply'); } });
  const f = file(); await h.ui.send(f.file, 'peer', false);
  assert.equal(h.uploads.size, 1);
  assert.deepEqual([...h.uploads.values()][0].data, f.bytes);
  assert.equal([...h.uploads.values()][0].finishes, 1);
  assert.equal(h.calls.filter(p => p.action === 'cancelUpload').length, 0);
  const repeated = h.calls.filter(p => p.action === 'append' && p.offset === '49152');
  assert.equal(repeated.length, 2); assert.deepEqual(repeated[0], repeated[1]);
});

test('exhausted retries retain input and Resume continues from the host offset', async () => {
  let offline = true;
  const h = harness((p, phase) => { if (offline && p.action === 'append' && p.offset === '49152' && phase === 'after') throw Error('offline'); });
  const f = file(); await h.ui.send(f.file, 'peer', false);
  assert.equal(h.calls.filter(p => p.action === 'append' && p.offset === '49152').length, 3);
  assert.equal([...h.uploads.values()][0].data.length, 98304);
  assert.equal(h.calls.filter(p => p.action === 'cancelUpload').length, 0);
  assert.equal(h.panel.querySelector('.resume').hidden, false);
  const before = h.calls.length; offline = false;
  await h.panel.querySelector('.resume').onclick();
  const retry = h.calls.slice(before);
  assert.equal(retry.find(p => p.action === 'append').offset, '98304');
  assert.equal(new Set(h.calls.filter(p => p.id).map(p => p.id)).size, 1);
  assert.deepEqual([...h.uploads.values()][0].data, f.bytes);
});

test('a hidden interrupted upload can be reopened from File transfers', async () => {
  const h = harness((p, phase) => { if (p.action === 'append' && phase === 'before') throw Error('offline'); });
  await h.ui.send(file().file, 'peer', false);
  h.panel.querySelector('.hide').onclick(); await h.ui.list();
  const entries = h.panel.querySelector('.file-list').items;
  assert.equal(entries.length, 1); assert.match(entries[0].textContent, /Upload paused/);
  entries[0].onclick(); assert.equal(h.panel.querySelector('.resume').hidden, false);
  await h.panel.querySelector('.cancel').onclick();
  assert.equal(h.uploads.size, 0);
  assert.equal(h.calls.filter(p => p.action === 'cancelUpload').length, 1);
});

test('lost begin and finish responses do not create duplicate uploads or offers', async () => {
  const lost = new Set();
  const h = harness((p, phase) => { if (['begin', 'finish'].includes(p.action) && phase === 'after' && !lost.has(p.action)) { lost.add(p.action); throw Error('lost reply'); } });
  await h.ui.send(file(0).file, 'peer', false);
  assert.equal(h.uploads.size, 1); assert.equal([...h.uploads.values()][0].finishes, 1);
  assert.equal(h.calls.filter(p => p.action === 'begin').length, 2);
  assert.equal(h.calls.filter(p => p.action === 'finish').length, 2);
  assert.equal(h.calls.filter(p => p.action === 'cancelUpload').length, 0);
});

test('bad offset responses pause safely without deleting accepted bytes', async () => {
  const h = harness((p, phase) => p.action === 'append' && phase === 'after' ? {ok: 'true', offset: '-1'} : undefined);
  await h.ui.send(file().file, 'peer', false);
  assert.match(h.panel.querySelector('.file-status').textContent, /Invalid upload offset/);
  assert.equal(h.calls.filter(p => p.action === 'finish').length, 0);
  assert.equal(h.calls.filter(p => p.action === 'cancelUpload').length, 0);
});
