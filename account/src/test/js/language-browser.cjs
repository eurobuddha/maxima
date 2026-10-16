// Real browser verification against synthetic accounts. No real sessions or relay traffic.
// PARLONS_PLAYWRIGHT_MODULE may point at an existing Playwright installation.
const { chromium } = require(process.env.PARLONS_PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const root = path.resolve(__dirname, '../../../..');
const assets = path.join(root, 'account/src/main/resources/panel');
const screenshots = path.join(root, 'build/localization/screenshots');
fs.mkdirSync(screenshots, { recursive: true });
const server = http.createServer((req, res) => {
  if (req.url.startsWith('/events')) { res.writeHead(200, {'Content-Type':'text/event-stream'}); res.write(': synthetic\n\n'); return; }
  const name = req.url.split('?')[0] === '/' ? '/index.html' : req.url.split('?')[0];
  const file = path.resolve(assets, '.' + name);
  if (!file.startsWith(assets + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) { res.writeHead(404); res.end(); return; }
  res.setHeader('Content-Type',file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : file.endsWith('.html') ? 'text/html' : 'application/octet-stream');
  res.end(fs.readFileSync(file));
});
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({headless:true, ...(process.env.PARLONS_CHROME ? {executablePath:process.env.PARLONS_CHROME} : {})});
  try {
    const context = await browser.newContext({viewport:{width:1024,height:800},locale:'ru-RU'});
    const page = await context.newPage(), errors = [], requests = [], fileActions = [];
    let releaseUpload; const uploadGate = new Promise(resolve => { releaseUpload = resolve; });
    page.on('pageerror',error=>errors.push(error.message));
    // Keep real call UI; substitute only transport/media acquisition.
    await page.route('**/calls.js',route=>route.fulfill({contentType:'text/javascript',body:`window.ParlonsCalls=class { constructor(options){window.syntheticCalls=this;this.options=options;this.client='test';} };`}));
    await page.route('**/api/**',async route=>{
      const method = new URL(route.request().url()).pathname.slice(5); requests.push(method);
      if (method === 'files') {
        const body=route.request().postDataJSON();fileActions.push(body.action);
        if (body.action==='append') await uploadGate;
        const reply=body.action==='begin'?{ok:'true',offset:'0'}:body.action==='append'?{ok:'true',offset:'1'}:
          body.action==='status'?{ok:'true',status:'Preparing',size:'1',done:'1'}:{ok:'true'};
        await route.fulfill({contentType:'application/json',body:JSON.stringify(reply)});return;
      }
      const fixtures = {
        ping:{name:'Synthetic owner',permanent:'MAX#synthetic'},
        'node.status':{version:'test',hosts:2,hostedAccounts:true}, 'settings.get':{readReceipts:false},
        'identity.setname':{ok:false,error:'node error: settings unavailable'},
        'node.figures':{ownRelayState:'unreachable',ownRelay:'synthetic:9001',relayPort:9001,lanIp:'192.0.2.10',portOpen:'false',hosts:[{host:'synthetic:9001',connected:true}],builtin:true},
        'admin-password':{available:true,configured:false},
        'hosted.accounts':{enabled:true,online:true,managed:true,access:'chat-only',accounts:[{name:'Chats',state:'running',paused:false}],invite:'synthetic-invite-Chats'},
        'chat.summaries':(route.request().postDataJSON() || {}).offset ? {summaries:[
          {peer:'p2',name:'Literal label',last:'📷 Photo',time:1},
          {peer:'p3',name:'Voice 中文',last:'🎤 Voice note  0:42',lastPreview:{kind:'voice',text:'0:42'},time:0}
        ],more:false,next:3} : {summaries:[{peer:'p1',name:'Chats',last:'📷 Photo  中文 <img>',lastPreview:{kind:'photo',text:'中文 <img>'},time:Date.now()+1000}],more:true,next:1},
        'chat.conversation':{messages:[{id:'m1',body:'Chats',time:Date.now(),mine:false}]},
        'contacts.info':{name:'Chats',lastSeen:Date.now()},'chat.search':{messages:[]},
        'minimadocs/status':{connections:0},'chat.markread':{}
      };
      await route.fulfill({contentType:'application/json',body:JSON.stringify(fixtures[method]||{})});
    });
    await page.goto('http://127.0.0.1:'+server.address().port);
    await page.locator('#languageDialog').waitFor();
    assert.equal(await page.evaluate(()=>window.ParlonsI18n.getLanguage()),'en');
    await page.getByRole('button',{name:'简体中文',exact:true}).click();
    assert.equal(await page.locator('[data-route="chats"]').textContent(),'聊天');
    await page.waitForFunction(()=>document.querySelectorAll('.conv').length===3);
    assert.equal(await page.locator('.conv .prev').nth(0).textContent(),'📷 照片  中文 <img>');
    assert.equal(await page.locator('.conv .prev').nth(1).textContent(),'📷 Photo');
    assert.equal(await page.locator('.conv .prev').nth(2).textContent(),'🎤 语音消息  0:42');
    assert.equal(await page.locator('.conv .prev img').count(),0);
    await page.screenshot({path:path.join(screenshots,'summary-chinese.png')});
    await page.locator('[data-route="settings"]').click();
    await page.locator('#setName').fill('Unsubmitted profile 中文');
    await page.locator('#saveName').click();
    await page.waitForFunction(()=>document.getElementById('toast').textContent==='节点错误：设置暂不可用');
    await page.evaluate(()=>window.originalProfile=document.getElementById('setName'));
    await page.locator('.language-change').click();
    await page.getByRole('button',{name:'Русский',exact:true}).click();
    assert.equal(await page.locator('#setName').inputValue(),'Unsubmitted profile 中文');
    assert.equal(await page.locator('#toast').textContent(),'Ошибка узла: Настройки недоступны');
    assert.ok(await page.evaluate(()=>window.originalProfile===document.getElementById('setName')));
    await page.setViewportSize({width:390,height:844});
    await page.locator('.language-change').click();
    await page.screenshot({path:path.join(screenshots,'language-selector-mobile.png')});
    await page.getByRole('button',{name:'Русский',exact:true}).click();
    await page.locator('[data-route="chats"]').click();
    await page.locator('.conv').first().click();
    await page.locator('#draft').fill('Привет 中文 unsent');
    await page.evaluate(()=>{window.originalDraft=document.getElementById('draft'); window.originalDraft.setSelectionRange(3,6);window.originalSummary=document.querySelector('.conv .prev');});
    const conversations=requests.filter(x=>x==='chat.conversation').length;
    await page.evaluate(()=>window.ParlonsI18n.chooser());
    await page.getByRole('button',{name:'English',exact:true}).click();
    assert.equal(await page.locator('#draft').inputValue(),'Привет 中文 unsent');
    assert.ok(await page.evaluate(()=>window.originalDraft===document.getElementById('draft')));
    assert.equal(await page.evaluate(()=>document.getElementById('draft').selectionStart),3);
    assert.equal(requests.filter(x=>x==='chat.conversation').length,conversations);
    assert.equal(await page.locator('.bubble .body').textContent(),'Chats');
    assert.equal(await page.locator('.conv .prev').first().textContent(),'📷 Photo  中文 <img>');
    assert.ok(await page.evaluate(()=>window.originalSummary===document.querySelector('.conv .prev')));
    assert.equal(await page.locator('.ctitle').textContent(),'Chats');
    assert.equal(await page.locator('.daypill').textContent(),'Today');
    assert.equal(await page.locator('#csub').textContent(),'online');
    assert.ok(await page.locator('#csub').evaluate(el=>el.classList.contains('online')));
    await page.locator('#attachFile').setInputFiles({name:'Chats.bin',mimeType:'application/octet-stream',buffer:Buffer.from([1])});
    await page.locator('.file-status').waitFor();
    await page.waitForFunction(()=>document.querySelector('.file-status').textContent==='Uploading to your account…');
    await page.evaluate(()=>{window.originalUpload=document.getElementById('sheet');window.ParlonsI18n.chooser();});
    await page.getByRole('button',{name:'简体中文',exact:true}).click();
    assert.equal(await page.locator('.file-status').textContent(),'正在上传到您的账户…');
    assert.equal(await page.locator('#sheet .h').textContent(),'正在发送 Chats.bin');
    assert.ok(await page.evaluate(()=>window.originalUpload===document.getElementById('sheet')));
    releaseUpload();await page.locator('.file-action').waitFor();
    assert.equal(fileActions.filter(x=>x==='begin').length,1);
    assert.equal(fileActions.filter(x=>x==='append').length,1);
    assert.equal(fileActions.filter(x=>x==='finish').length,1);
    await page.locator('#sheetClose').click();
    await page.evaluate(()=>{
      const c={id:'synthetic-call',peer:'p1',name:'Chats',state:'LIVE',video:true,media:{},liveSince:Date.now()};
      window.syntheticCalls.current=c; window.syntheticCalls.options.changed(c);
      window.originalVideo=document.getElementById('callRemote');
      window.originalStream=new MediaStream(); window.originalVideo.srcObject=window.originalStream;
      window.ParlonsI18n.setLanguage('ru');
    });
    assert.match(await page.locator('#callStatus').textContent(),/^Видеозвонок/);
    assert.equal(await page.locator('#callMute').textContent(),'Выключить микрофон');
    assert.ok(await page.evaluate(()=>window.originalVideo===document.getElementById('callRemote')&&window.originalVideo.srcObject===window.originalStream));
    assert.equal(await page.title(),'Parlons — LIVE');
    await page.screenshot({path:path.join(screenshots,'call-russian-mobile.png')});
    await page.evaluate(()=>{window.syntheticCalls.current=null;window.syntheticCalls.options.changed({state:'ENDED',reason:'Call ended'});});
    await page.reload();
    assert.equal(await page.locator('html').getAttribute('lang'),'ru');
    assert.equal(await page.locator('#languageDialog').count(),0);
    await page.locator('#back').click();
    await page.locator('[data-route="devices"]').click();
    await page.locator('#newPassword').fill('synthetic password');
    await page.locator('#confirmPassword').fill('different password');
    await page.locator('#savePassword').click();
    assert.equal(await page.locator('#passwordResult').textContent(),'Пароли не совпадают.');
    await page.locator('#tenantInvite').click();
    await page.waitForFunction(()=>document.getElementById('tenantInviteText').textContent==='synthetic-invite-Chats');
    await page.evaluate(()=>window.ParlonsI18n.setLanguage('zh-Hans'));
    assert.equal(await page.locator('#newPassword').inputValue(),'synthetic password');
    assert.equal(await page.locator('#passwordResult').textContent(),'两次输入的密码不一致。');
    assert.equal(await page.locator('#tenantInviteText').textContent(),'synthetic-invite-Chats');
    assert.match(await page.locator('#hostingResult').textContent(),/这是 Chats 的邀请/);
    await page.locator('#hostingRefresh').click();
    await page.waitForFunction(()=>document.getElementById('hostingResult').textContent==='');
    await page.evaluate(()=>window.ParlonsI18n.setLanguage('ru'));
    assert.equal(await page.locator('#hostingResult').textContent(),'');
    assert.equal(await page.locator('#hostingToggle').textContent(),'Остановить хостинг');
    assert.equal(await page.locator('#tenantInvite').isDisabled(),false);
    await page.screenshot({path:path.join(screenshots,'devices-russian-mobile.png')});
    await page.locator('[data-route="node"]').click();
    await page.locator('#hostAdd').waitFor();
    await page.locator('#hostAdd').fill('unsent.example:9001');
    await page.evaluate(()=>window.ParlonsI18n.setLanguage('zh-Hans'));
    assert.equal(await page.locator('#hostAdd').inputValue(),'unsent.example:9001');
    assert.equal(await page.locator('#builtin').getAttribute('aria-label'),'使用内置中继列表');
    await page.screenshot({path:path.join(screenshots,'node-chinese-mobile.png')});
    assert.deepEqual(errors,[]);
    console.log('PASS: manual selection, persistence, live switching, untouched user text/drafts, uninterrupted upload, call media/state and mobile screenshots');
    await context.close();
  } finally { await browser.close(); server.closeAllConnections(); await new Promise(resolve=>server.close(resolve)); }
})().catch(error=>{console.error(error);process.exitCode=1;server.closeAllConnections();server.close();});
