const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { execFileSync } = require('node:child_process');
const root = path.resolve(__dirname, '../../../..');
function harness(saved, blocked = false) {
  const values = new Map(saved ? [['parlons.language', saved]] : []), nodes = [];
  const document = {documentElement: {}, querySelectorAll: () => nodes};
  const localStorage = {getItem(key) { if (blocked) throw Error('Blocked'); return values.get(key); },
    setItem(key, value) { if (blocked) throw Error('Blocked'); values.set(key,value); }};
  const window = {localStorage, navigator: {language:'ru',languages:['ru','zh-CN']}};
  const context = vm.createContext({window,document});
  for (const name of ['messages.js','i18n.js']) vm.runInContext(fs.readFileSync(path.join(root,'account/src/main/resources/panel',name),'utf8'),context);
  function node(attributes, text = '') {
    const el = {value:'草稿 draft', textContent:text, getAttribute:k=>attributes[k],setAttribute(k,v){attributes[k]=v;},removeAttribute(k){delete attributes[k];}};
    nodes.push(el); return el;
  }
  return {i:window.ParlonsI18n,document,values,node};
}
test('all catalog entries have translations and generated resources match the catalog', () => {
  execFileSync(process.execPath,[path.join(root,'tools/localization/generate.cjs'),'--check']);
});
test('literal panel translation references resolve in the shared catalog', () => {
  const catalog=JSON.parse(fs.readFileSync(path.join(root,'localization/catalog.json'),'utf8'));
  for (const file of ['app.js','calls-ui.js','private-files.js','index.html']) {
    const source=fs.readFileSync(path.join(root,'account/src/main/resources/panel',file),'utf8');
    for (const pattern of [/\b(?:tr|label|setState)\(\s*['"]([a-zA-Z]+\.[\w.]+)['"]/g, /data-i18n(?:-(?:title|placeholder|aria-label|alt))?="([a-zA-Z]+\.[\w.]+)"/g]) {
      for (const match of source.matchAll(pattern)) assert.ok(Object.hasOwn(catalog,match[1]),file+': '+match[1]);
    }
  }
});
test('language is explicitly chosen and persisted, never inferred from the browser', () => {
  const h=harness(); assert.equal(h.i.getLanguage(),'en'); assert.equal(h.i.hasChoice(),false);
  h.i.setLanguage('zh-Hans'); assert.equal(h.values.get('parlons.language'),'zh-Hans');
  assert.equal(h.i.t('nav.chats'),'聊天'); assert.equal(h.document.documentElement.lang,'zh-Hans');
  assert.equal(harness('zh-Hans').i.getLanguage(),'zh-Hans');
  assert.equal(harness('unsupported').i.hasChoice(),false);
  assert.throws(()=>h.i.setLanguage('unsupported'),/Unsupported language/);
  assert.equal(h.i.getLanguage(),'zh-Hans');
});
test('storage restrictions do not prevent manual language selection', () => {
  const h=harness(null,true); h.i.setLanguage('ru'); assert.equal(h.i.t('nav.chats'),'Чаты');
});
test('Russian plurals use one, few and many correctly', () => {
  const h=harness('ru');
  for (const [count,text] of [[1,'1 ретранслятор'],[2,'2 ретранслятора'],[5,'5 ретрансляторов'],[11,'11 ретрансляторов'],[21,'21 ретранслятор'],[22,'22 ретранслятора']]) {
    assert.equal(h.i.t('connection.relays',{count}),text);
  }
});
test('switching changes only explicit UI labels and preserves drafts and user content', () => {
  const h=harness(), label=h.node({'data-i18n':'nav.chats'}), draft=h.node({'data-i18n-placeholder':'chat.message'});
  const message=h.node({},'Chats'); let changed=0; const unsubscribe=h.i.onChange(()=>changed++);
  h.i.setLanguage('ru'); assert.equal(label.textContent,'Чаты'); assert.equal(draft.value,'草稿 draft');
  assert.equal(message.textContent,'Chats'); assert.equal(changed,1); unsubscribe();
  h.i.setLanguage('en'); assert.equal(label.textContent,'Chats'); assert.equal(changed,1);
});
test('dynamic counts retranslate in place without replacing their elements', () => {
  const h=harness(), count=h.node({}); h.i.bind(count,'connection.relays',{count:2});
  assert.equal(count.textContent,'2 relays'); h.i.setLanguage('ru'); assert.equal(count.textContent,'2 ретранслятора');
});
test('cleared status bindings cannot reappear when the language changes', () => {
  const h=harness(), status=h.node({});
  h.i.bind(status,'common.loading'); h.i.text(status,''); h.i.setLanguage('ru');
  assert.equal(status.textContent,'');
  h.i.render(status,()=>h.i.t('hosting.inviteHelp',{name:'Chats'}));
  h.i.setLanguage('zh-Hans'); assert.match(status.textContent,/Chats/);
  h.i.text(status,'raw identity'); h.i.setLanguage('en'); assert.equal(status.textContent,'raw identity');
});
test('placeholder content remains literal and does not recursively translate', () => {
  const h=harness('zh-Hans'); assert.equal(h.i.t('presence.lastSeen',{time:'<img onerror=x>{count}'}),'上次在线：<img onerror=x>{count}');
});

test('fixed account-server refusals have translations and unknown diagnostics remain literal', () => {
  const h = harness(), catalog = JSON.parse(fs.readFileSync(path.join(root,'localization/catalog.json'),'utf8'));
  const english = new Set(Object.values(catalog).map(entry => entry.en));
  const server = fs.readFileSync(path.join(root,'account/src/main/java/com/eurobuddha/maxima/cloud/ParlonsControl.java'),'utf8');
  for (const match of server.matchAll(/err\("([^"\n]*)"\)/g)) assert.ok(english.has(match[1]), match[1]);
  for (const code of ['ru','zh-Hans']) {
    h.i.setLanguage(code);
    for (const [key, value] of Object.entries(catalog).filter(([key]) => key.startsWith('serverError.'))) {
      assert.equal(h.i.system(value.en), value[code], key);
      assert.notEqual(value.en, value[code], key);
    }
    for (const raw of ['unrecognized failure {error} $1 中文', '<img onerror=x>', 'no such contact extra'])
      assert.equal(h.i.system(raw), raw);
  }
});

test('dynamic server diagnostics preserve literal values, nested errors and matching boundaries', () => {
  const h = harness(), literal = 'Copy 中文 $1 {error} <img>';
  for (const code of ['en', 'ru', 'zh-Hans']) {
    h.i.setLanguage(code);
    for (const [prefix, key] of [
      ['resync refused: ', 'serverError.resyncRefused'], ['node error: ', 'serverError.nodeFailure'],
      ['hosting failed: ', 'serverError.hostFailure'], ['could not create the collection folder: ', 'serverError.collectionFolder'],
      ['could not list: ', 'serverError.listFailure'], ['could not delete: ', 'serverError.deleteFailure']
    ]) {
      assert.equal(h.i.system(prefix + literal), h.i.t(key, {error:literal}));
      assert.equal(h.i.system(prefix + 'no such contact'), h.i.t(key, {error:h.i.t('serverError.noContact')}));
      assert.equal(h.i.system(prefix + 'Copy'), h.i.t(key, {error:'Copy'}));
    }
    for (const [prefix, key] of [['unknown contact ', 'iosError.unknownContact'], ['chunk unavailable: ', 'iosError.chunkUnavailable']])
      assert.equal(h.i.system(prefix + literal), h.i.t(key, {id:literal}));
    const number = '00012345678901234567890';
    assert.equal(h.i.system('can only RAISE above the current ' + number), h.i.t('serverError.counterAboveCurrent', {current:number}));
    assert.equal(h.i.system("above the key's maximum " + number), h.i.t('serverError.counterAboveMaximum', {maximum:number}));
    assert.equal(h.i.system('a group holds at most 128 members (129 asked)'), h.i.t('serverError.groupLimit', {maximum:'128',requested:'129'}));
    for (const raw of ['can only RAISE above the current -1', 'can only RAISE above the current 1\n', "above the key's maximum 1e9", 'a group holds at most 128 members (129 asked)\n'])
      assert.equal(h.i.system(raw), raw);
    const tail = 'node error: '.repeat(4) + literal;
    assert.ok(h.i.system('node error: '.repeat(8) + tail).endsWith(tail));
  }
});

test('count-dependent wallet and group messages use grammatical forms and preserve batch data', () => {
  const h = harness('ru'), editions = [[0,'экземпляров'],[1,'экземпляр'],[2,'экземпляра'],[5,'экземпляров'],[11,'экземпляров'],[21,'экземпляр'],[22,'экземпляра'],[112,'экземпляров']];
  for (const [count, noun] of editions) assert.equal(h.i.t('nodeMint.editionLimit',{count}), `${count} ${noun} (требуется разделение)`);
  assert.match(h.i.t('nodeNft.unconfirmed',{count:1}), /^После 1 проверки /);
  assert.match(h.i.t('nodeNft.unconfirmed',{count:2}), /^После 2 проверок /);
  assert.match(h.i.t('nativeChat.groupCapacity',{count:21}), /21 человека/);
  const batch = '中文 {count} $1';
  assert.equal(h.i.t('nodeTools.postingBatch',{batch,count:1}), `Распределение: публикация пакета ${batch} (1 адрес)…`);
  assert.equal(h.i.t('nodeTools.postingBatch',{batch,count:22}), `Распределение: публикация пакета ${batch} (22 адреса)…`);
  h.i.setLanguage('en');
  assert.equal(h.i.t('nodeSend.decimals',{count:1}), 'This token supports at most 1 decimal place.');
  assert.equal(h.i.t('nodeSend.decimals',{count:8}), 'This token supports at most 8 decimal places.');
  assert.match(h.i.t('androidChat.groupLimit',{count:1}), /1 person /);
  assert.equal(h.i.t('nodeTools.postingBatch',{batch:3,count:1}), 'Distribute: posting batch 3 (1 address)…');
  h.i.setLanguage('zh-Hans');
  for (const count of [0,1,2,5,21,22]) assert.equal(h.i.t('nodeMint.editionLimit',{count}), `${count} 个版本（需要拆分）`);
});
