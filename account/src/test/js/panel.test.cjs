const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// Exercise the shipped script in a VM with just its browser boundary stubbed. No accounts,
// sockets, production test hooks or duplicate implementation of the async state machine.
const source = fs.readFileSync(process.env.PARLONS_PANEL_SOURCE || path.join(__dirname, '../../main/resources/panel/app.js'), 'utf8');
function element() {
  const classes = new Set(), attrs = {}, handlers = {}, children = new Map();
  return { nodeType: 1, attrs, handlers, disabled: false, value: '', appended: [], parentElement: {}, textContent: '', innerHTML: '',
    classList: { contains: k => classes.has(k), toggle(k, on) { on ? classes.add(k) : classes.delete(k); } },
    addEventListener(k, f) { handlers[k] = f; }, setAttribute(k,v) { attrs[k] = v; }, getAttribute(k) { return attrs[k] || null; }, removeAttribute(k) { delete attrs[k]; },
    querySelector(k) { if (!children.has(k)) children.set(k, element()); return children.get(k); },
    appendChild(child) { this.appended.push(child); }, remove() {}, focus() {}, isConnected: false, querySelectorAll() { return []; }, style: {}, scrollHeight: 100, scrollTop: 0, clientHeight: 100 };
}
function harness(fetch) {
  const nodes = new Map();
  const node = id => { if (!nodes.has(id)) nodes.set(id, element()); return nodes.get(id); };
  const renders = { page: 0, list: 0, messages: 0 };
  const context = vm.createContext({ fetch, document: { body: element(), createElement: () => ({content: {firstElementChild: element()}}), getElementById: node, querySelectorAll: () => [], addEventListener() {}, documentElement: element() },
    window: { icon: () => '', addEventListener() {} }, confirm: () => true, localStorage: { getItem: () => null },
    setTimeout() {}, clearTimeout() {}, Event: class { constructor(type) { this.type = type; } }, URL, Uint8Array, crypto: require('node:crypto').webcrypto, TextDecoder, atob: s => Buffer.from(s, 'base64').toString('binary'), btoa: s => Buffer.from(s, 'binary').toString('base64'), renders });
  const expose = `globalThis.panel = { S, api, toast, retryCard, showSendErr, summaryPreview, i18n: I, draftKeydown, renderAdminPassword, renderHostedAccounts, linkText, bubbleHtml, chatPhotos, copyableMessage, insertEmoji, findMessage, refreshPill, loadOlder, reloadOpenTail, loadSummaries, sendFile,
    wireSwitch: typeof wireSwitch === 'function' ? wireSwitch : null,
    select(peer, group = false) { ++openSeq; S.open = peer; S.openIsGroup = group; S.msgs = [{id: peer, time: 100}]; olderBusy = false; olderDone = false; },
    get seq() { return openSeq; }, get busy() { return olderBusy; }, get done() { return olderDone; } };
    renderChats = () => { renders.page++; }; renderChatsList = () => { renders.list++; }; renderMsgs = () => { renders.messages++; };`;
  assert.match(source, /  boot\(\);\s*\}\)\(\);\s*$/);
  for (const name of ['messages.js', 'i18n.js', 'private-files.js']) vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/panel', name), 'utf8'), context);
  vm.runInContext(source.replace(/  boot\(\);\s*\}\)\(\);\s*$/, expose + '\n})();'), context);
  return { p: context.panel, node, renders };
}
const reply = (body, status = 200) => ({ok: status < 400, status, json: async () => body});
function deferred() { let resolve; const promise = new Promise(r => resolve = r); return {promise, resolve}; }

test('server failures translate in retained displays while raw API errors and drafts stay intact', async () => {
  const raw = 'no such contact', h = harness(async () => reply({ok:false,error:raw}, 400));
  let failure;
  try { await h.p.api('contacts.rename', {name:'literal'}); } catch (e) { failure = e; }
  assert.equal(failure.message, raw);
  h.node('draft').value = 'no such contact 中文 {error}';
  h.p.toast(failure.message, 'err'); h.p.showSendErr(failure.message);
  const host = element(); h.p.retryCard(host, 'chat.refreshFailed', failure.message, () => {});
  const displays = [h.node('toast'), h.node('sendErr'), host.appended[0].querySelector('.sub')];
  for (const code of ['ru', 'zh-Hans', 'en']) {
    h.p.i18n.setLanguage(code);
    for (const display of displays) {
      h.p.i18n.apply(display);
      assert.equal(display.textContent, h.p.i18n.t('serverError.noContact'));
    }
    assert.equal(failure.message, raw);
    assert.equal(h.node('draft').value, 'no such contact 中文 {error}');
  }
  const literal = '<img onerror=x> {error} $1 中文';
  h.p.toast(literal, 'err'); h.p.i18n.setLanguage('ru'); h.p.i18n.apply(h.node('toast'));
  assert.equal(h.node('toast').textContent, literal);
  h.p.toast('success literal', 'ok'); h.p.i18n.setLanguage('zh-Hans'); h.p.i18n.apply(h.node('toast'));
  assert.equal(h.node('toast').textContent, 'success literal');
});

test('Enter commits an input-method composition without sending the draft', () => {
  const h = harness(() => { throw Error('Composition must not send'); });
  h.node('draft').value = '中文';
  for (const composition of [{isComposing:true}, {keyCode:229}]) {
    h.p.draftKeydown({key:'Enter', preventDefault() { throw Error('Composition must keep its Enter event'); }, ...composition});
    assert.equal(h.node('draft').value, '中文');
  }
});

test('admin setup validates confirmation, clears secrets and unlocks invitations only after saving', async () => {
  const calls = [], h = harness(async (url, options) => {
    calls.push({url, options});
    return reply(options.method === 'POST' ? {ok:true,configured:true} : {available:true,configured:false});
  });
  const body = element(), input = element(), button = element();
  await h.p.renderAdminPassword(body, input, button);
  assert.equal(button.disabled, true); assert.equal(input.hidden, true);
  const form = body.appended[0], password = form.querySelector('#newPassword'), confirmation = form.querySelector('#confirmPassword');
  password.value = 'synthetic password'; confirmation.value = 'different password';
  await form.handlers.submit({preventDefault(){}});
  assert.equal(calls.length, 1); assert.match(form.querySelector('#passwordResult').textContent, /do not match/);
  confirmation.value = password.value;
  await form.handlers.submit({preventDefault(){}});
  assert.equal(calls[1].url, '/api/admin-password');
  assert.equal(JSON.parse(calls[1].options.body).newPassword, 'synthetic password');
  assert.equal(password.value, ''); assert.equal(confirmation.value, '');
  assert.equal(button.disabled, false); assert.equal(input.hidden, false);
  assert.equal(form.querySelector('#currentPassword').required, true);
});

test('failed password setup stays unset and permits a retry without retaining passwords', async () => {
  const h = harness(async (url, options) => options.method === 'POST' ? reply({error:'Disk is full'}, 400) : reply({available:true,configured:false}));
  const body=element(), input=element(), button=element(); await h.p.renderAdminPassword(body,input,button);
  const form=body.appended[0]; form.querySelector('#newPassword').value=form.querySelector('#confirmPassword').value='synthetic password';
  await form.handlers.submit({preventDefault(){}});
  assert.equal(button.disabled,true); assert.equal(form.querySelector('#savePassword').disabled,false);
  assert.equal(form.querySelector('#newPassword').value,''); assert.match(form.querySelector('#passwordResult').textContent,/Disk is full/);
});

test('tenant UI enables hosting and uses the separate account invite endpoint', async () => {
  let enabled=false; const calls=[];
  const h=harness(async (url, options) => {
    const request=JSON.parse(options.body); calls.push({url,...request});
    if(request.action==='enable') enabled=true;
    if(request.action==='invite') return reply({ok:true,invite:'MAX#guest#MxTEST@h:1?code=AABB-CCDD-EEFF'});
    return reply({ok:true,managed:true,enabled,online:enabled,access:'chat-only',accounts:enabled?[{name:'alice',state:'running',paused:false}]:[]});
  });
  const body=element(); await h.p.renderHostedAccounts(body); const card=body.appended[0];
  assert.equal(card.querySelector('#hostingCreate').hidden,true);
  await card.querySelector('#hostingToggle').onclick();
  // The click handler starts the async request; drain the promise microtasks.
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(card.querySelector('#hostingCreate').hidden,false);
  const row=card.querySelector('#hostingRows').appended[0];
  await row.querySelector('#tenantInvite').onclick();
  assert.equal(card.querySelector('#tenantInvitation').hidden,false);
  assert.match(card.querySelector('#tenantInviteText').textContent,/MAX#guest/);
  assert.ok(calls.every(c=>c.url==='/api/hosted.accounts'));
  assert.equal(calls.at(-1).name,'alice');
});

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
  const d = deferred(), calls = [], h = harness(async (url, options) => {
    const p = JSON.parse(options.body); calls.push(p);
    return reply({ok: true, offset: p.action === 'begin' ? '0' : '1'});
  });
  h.p.select('group', true);
  const upload = h.p.sendFile({type: 'text/plain', name: 'hello.txt', size: 1, slice: () => ({arrayBuffer: () => d.promise})}, false);
  h.p.select('person', false); d.resolve(new Uint8Array([65]).buffer); await upload;
  assert.deepEqual(calls.map(c => c.action), ['begin', 'append', 'finish']); assert.equal(calls[0].peer, 'group'); assert.equal(calls[0].group, true);
  assert.deepEqual(Array.from(h.p.S.msgs, x => x.id), ['person']); assert.equal(h.renders.messages, 0);
});
test('switch saves once, reflects success after the event ends and preserves value on failure', async () => {
  const h = harness(), button = element(), d = deferred(); let calls = 0;
  h.p.wireSwitch(button, 'settings.readReceipts', () => { calls++; return d.promise; }, () => 'Saved');
  const save = button.handlers.click(); await button.handlers.click(); assert.equal(calls, 1); assert.equal(button.disabled, true);
  d.resolve(); await save; assert.equal(button.attrs['aria-checked'], 'true'); assert.equal(button.disabled, false);
  h.p.wireSwitch(button, 'settings.readReceipts', async () => { throw new Error('No connection'); }, () => 'Saved');
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

test('chat links preserve literal text, balanced punctuation and query strings', () => {
  const h = harness(async () => reply({}));
  const out = h.p.linkText('🙂 (https://example.org/a_(b)). www.example.org/x?a=1&b=2!');
  assert.match(out, /href="https:\/\/example.org\/a_\(b\)"/);
  assert.match(out, /<\/a>\)\./);
  assert.match(out, /href="https:\/\/www.example.org\/x\?a=1&amp;b=2"/);
  assert.equal((out.match(/target="_blank" rel="noopener noreferrer"/g) || []).length, 2);
});
test('chat rendering escapes HTML and does not link executable schemes or credentials', () => {
  const h = harness(async () => reply({}));
  const text = '<img src=x onerror=alert(1)> javascript:alert(1) file:///tmp/x https://user:pass@example.org';
  const out = h.p.bubbleHtml({body: text, time: 0, id: 'x'});
  assert.ok(out.includes('&lt;img')); assert.ok(!out.includes('<img')); assert.ok(!out.includes('class="chat-link"'));
  assert.ok(h.p.bubbleHtml({body:'https://example.org',time:0,id:'x'}).includes('class="chat-link"'));
});

test('bare domains retain paths without extracting hosts from email or rejected schemes', () => {
  const h = harness();
  assert.match(h.p.linkText('Visit example.com/path?x=1&y=2.'), /href="https:\/\/example.com\/path\?x=1&amp;y=2"/);
  for (const text of ['user@example.com', 'mailto:user@example.com', 'ftp://example.com', 'file://example.com', 'javascript:example.com', 'https://user:pass@example.com', 'example.com_foo', 'example.com1', '-example.com', 'bad-.example.com', '0.6.131'])
    assert.ok(!h.p.linkText(text).includes('<a '), text);
});
test('copy actions use visible captions and addresses rather than protocol metadata', () => {
  const h = harness();
  assert.equal(h.p.copyableMessage({body: 'hello'}).text, 'hello');
  assert.equal(h.p.copyableMessage({body: '\u0001m\u0001image/jpeg\u0001data:image/jpeg;base64,AA==\u0001Holiday'}).text, 'Holiday');
  assert.equal(h.p.copyableMessage({body: '\u0001m\u0001audio/ogg\u0001ref\u00013|abc'}).text, '');
  assert.equal(h.p.copyableMessage({body: '\u0001c\u0001key\u0001Name\u0001MAX#address'}).text, 'MAX#address');
});
test('emoji insertion replaces the selection and preserves a multi-codepoint glyph', () => {
  const h = harness(), draft = { value: 'hello world!', selectionStart: 6, selectionEnd: 11,
    setRangeText(text, a, b) { this.value = this.value.slice(0,a) + text + this.value.slice(b); this.selectionStart = this.selectionEnd = a + text.length; },
    dispatchEvent(event) { this.event = event.type; } };
  h.p.insertEmoji(draft, '❤️'); h.p.insertEmoji(draft, '😀');
  assert.equal(draft.value, 'hello ❤️😀!'); assert.equal(draft.event, 'input');
});
test('search navigation loads history and scrolls to the exact result', async () => {
  let count = 0;
  const h = harness(async () => { count++; return reply({messages: [{id:'target',time:1,body:'needle'}]}); });
  h.p.select('peer'); const row = element(); row.dataset = {id:'target'};
  row.classList.add = key => { row.highlight = key; }; row.scrollIntoView = options => { row.scrolled = options.block; };
  h.node('msgs').querySelectorAll = () => [row];
  await h.p.findMessage({peer:'peer',id:'target',time:1}, h.p.seq);
  assert.equal(count, 1); assert.equal(row.scrolled, 'center'); assert.equal(row.highlight, 'search-hit');
});
test('search navigation stops after changing conversations or a failed history request', async () => {
  const d = deferred(), h = harness(() => d.promise); h.p.select('first');
  const pending = h.p.findMessage({peer:'first',id:'target',time:1}, h.p.seq);
  h.p.select('second'); d.resolve(reply({messages:[{id:'target',time:1}]})); await pending;
  assert.deepEqual(Array.from(h.p.S.msgs, m => m.id), ['second']);
  let count = 0; const bad = harness(async () => { count++; throw Error('offline'); }); bad.p.select('first');
  await bad.p.findMessage({peer:'first',id:'target',time:1}, bad.p.seq);
  assert.equal(count, 1); assert.match(bad.node('toast').textContent, /Could not load/);
});

test('excessive domain labels remain literal text', () => {
  const h = harness();
  assert.ok(!h.p.linkText('a.'.repeat(10000) + 'com').includes('<a '));
});


test('structured summaries translate only known labels in every language and keep cached fallback literal', () => {
  const h = harness(() => { throw Error('No network for preview'); });
  const literal = 'Photo 中文 $1 {caption} <img>';
  for (const [code, photo, voice, contact] of [['en','📷 Photo','🎤 Voice note','👤 Contact: '],['ru','📷 Фото','🎤 Голосовое сообщение','👤 Контакт: '],['zh-Hans','📷 照片','🎤 语音消息','👤 联系人：']]) {
    h.p.i18n.setLanguage(code);
    for (const [kind,text,expected] of [['photo',literal,photo+'  '+literal],['voice','0:42',voice+'  0:42'],['contact',literal,contact+literal],['file',literal,'📎 '+literal],['payment','💸 1.23456789 TOKEN','💸 1.23456789 TOKEN']]) {
      const summary = {last:'Legacy English',lastPreview:{kind,text}};
      const original = JSON.stringify(summary);
      assert.equal(h.p.summaryPreview(summary),expected);
      assert.equal(JSON.stringify(summary),original);
    }
    for (const lastPreview of [undefined,null,{kind:'future',text:'x'},{kind:'photo',text:2},{kind:{toString:2},text:'x'}])
      assert.equal(h.p.summaryPreview({last:'📷 Photo',lastPreview}),'📷 Photo');
  }
});


test('summary paging retains every typed row and keeps the existing list on a later-page failure', async () => {
  const calls = [], first = {peer:'a',last:'old',time:1,lastPreview:{kind:'photo',text:'中文'}};
  const h = harness(async (url, options) => {
    const offset = JSON.parse(options.body).offset; calls.push(offset);
    return reply(offset === 0 ? {summaries:[first],more:true,next:1} : {summaries:[{peer:'b',time:2}],more:false,next:2});
  });
  h.p.S.search = 'unsent 中文';
  await h.p.loadSummaries();
  assert.deepEqual(calls,[0,1]);
  assert.equal(h.p.S.summaries.length,2); assert.equal(h.p.S.summaries[1].lastPreview.text,'中文');
  assert.equal(h.p.S.search,'unsent 中文');
  const broken = harness(async (url, options) => {
    if (JSON.parse(options.body).offset) throw Error('offline');
    return reply({summaries:[first],more:true,next:1});
  });
  const existing = [{peer:'keep'}]; broken.p.S.summaries = existing;
  await assert.rejects(broken.p.loadSummaries(),/offline/);
  assert.equal(broken.p.S.summaries,existing);
  const stuck = harness(async () => reply({summaries:[],more:true,next:0}));
  await assert.rejects(stuck.p.loadSummaries());
});
