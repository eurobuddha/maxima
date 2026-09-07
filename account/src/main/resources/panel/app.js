// Parlons panel - the account's own chat window, served same-origin by ParlonsLocal.
// Every call is POST /api/<method> with the session cookie; live events arrive on /events (SSE).
// No framework, no build step; the page's CSP allows no inline script.
(function () {
  'use strict';

  // ---------- helpers ----------
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const el = (html) => { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstElementChild; };
  const MEDIA_MARK = 'm';

  async function api(method, body) {
    const r = await fetch('/api/' + method, {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {})
    });
    if (r.status === 401) { setState('signed out - open the panel with a fresh link', true); throw new Error('signed out'); }
    const j = await r.json().catch(() => ({ ok: false, error: 'bad reply' }));
    if (j && j.ok === false) throw new Error(j.error || method + ' failed');
    return j;
  }
  async function apiGet(name) {
    const r = await fetch('/api/' + name, { credentials: 'same-origin' });
    if (r.status === 401) { setState('signed out - open the panel with a fresh link', true); throw new Error('signed out'); }
    return r.json();
  }

  let toastTimer = null;
  function toast(msg, kind) {
    const t = $('toast'); t.textContent = msg; t.className = 'toast' + (kind === 'err' ? ' err' : ''); t.hidden = false;
    clearTimeout(toastTimer); toastTimer = setTimeout(() => { t.hidden = true; }, kind === 'err' ? 5000 : 2500);
  }
  function setState(text, bad) { const s = $('state'); s.textContent = text; s.className = 'state' + (bad ? ' bad' : ''); }

  function when(ms) {
    if (!ms) return '';
    const d = new Date(Number(ms)), now = new Date();
    const same = d.toDateString() === now.toDateString();
    return same ? d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
                : d.toLocaleDateString([], { day: 'numeric', month: 'short' }) + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }
  function dayOf(ms) { return new Date(Number(ms)).toDateString(); }
  function initial(name) { const s = String(name || '').trim(); return s ? s[0].toUpperCase() : '?'; }
  function shortKeyName(k) { return k; }   // identifiers are always shown whole

  // ---------- media bodies (core ChatMedia): m mime  ref  caption ----------
  function parseMedia(body) {
    if (!body || !body.startsWith(MEDIA_MARK)) return null;
    const rest = body.slice(MEDIA_MARK.length);
    const a = rest.indexOf(''); if (a < 0) return null;
    const mime = rest.slice(0, a);
    const rest2 = rest.slice(a + 1);
    const b = rest2.indexOf(''); if (b < 0) return null;
    return { mime, ref: rest2.slice(0, b), caption: rest2.slice(b + 1) };
  }
  function manifestJson(ref) {
    if (!ref.startsWith('mx1:')) return null;
    let b64 = ref.slice(4).replace(/-/g, '+').replace(/_/g, '/');
    while (b64.length % 4) b64 += '=';
    try { return decodeURIComponent(escape(atob(b64))); } catch (e) { try { return atob(b64); } catch (e2) { return null; } }
  }
  function mediaUrl(m) {
    if (m.ref.startsWith('data:')) return m.ref;
    const j = manifestJson(m.ref);
    return j ? '/media?m=' + encodeURIComponent(j) : '';
  }
  function preview(body) {
    const m = parseMedia(body);
    if (!m) return body;
    const kind = m.mime.startsWith('video') ? '🎥 Video' : m.mime.startsWith('audio') ? '🎤 Voice note' : '📷 Photo';
    let cap = m.caption;
    if (m.mime.startsWith('audio') && cap.indexOf('|') >= 0) cap = cap.slice(0, cap.indexOf('|'));
    return cap ? kind + '  ' + cap : kind;
  }

  // ---------- state ----------
  const S = {
    me: { name: '', permanent: '', primary: '' },
    summaries: [],
    open: null,            // peer key of the open conversation
    openIsGroup: false,
    openName: '',
    msgs: [],              // entries of the open conversation, ascending time
    route: 'chats',
    lastEvent: 0,          // newest event time seen (for chat.since after a reconnect)
    contacts: [],
    search: ''
  };

  // ---------- routing ----------
  function go(hash) { location.hash = hash; }
  window.addEventListener('hashchange', render);
  document.querySelectorAll('.nav button').forEach((b) => b.addEventListener('click', () => go('#' + b.dataset.route)));

  function render() {
    const h = location.hash.replace(/^#/, '') || 'chats';
    const [route, arg] = h.split('/');
    S.route = route;
    document.querySelectorAll('.nav button').forEach((b) => b.classList.toggle('on', b.dataset.route === (route === 'chat' ? 'chats' : route)));
    $('list').hidden = !(route === 'chats' || route === 'chat');
    $('app').classList.toggle('open', route !== 'chats');
    if (route === 'chat' && arg) { openChat(decodeURIComponent(arg)); }
    else if (route === 'contacts') { S.open = null; renderContacts(); }
    else if (route === 'devices') { S.open = null; renderDevices(); }
    else if (route === 'node') { S.open = null; renderNode(); }
    else if (route === 'settings') { S.open = null; renderSettings(); }
    else { S.open = null; renderEmpty(); renderList(); }
  }
  function renderEmpty() {
    $('pane').innerHTML = '';
    $('pane').appendChild(el('<div class="empty"><div class="emptyTitle">Your account is up.</div><div class="emptyText">Pick a conversation, or add someone in Contacts.</div></div>'));
  }

  // ---------- chats list ----------
  async function loadSummaries() {
    const r = await api('chat.summaries', { offset: 0, limit: 100 });
    S.summaries = r.summaries || [];
    S.summaries.sort((a, b) => Number(b.time || 0) - Number(a.time || 0));
    renderList();
  }
  function renderList() {
    const box = $('listItems');
    box.innerHTML = '';
    const q = S.search.trim().toLowerCase();
    let rows = S.summaries;
    if (q) rows = rows.filter((s) => (s.name || '').toLowerCase().includes(q) || (s.last || '').toLowerCase().includes(q) || (s.peer || '').toLowerCase().includes(q));
    if (!rows.length) {
      box.appendChild(el('<div class="empty small">' + (q ? 'Nothing matches.' : 'No conversations yet. Add a contact and say hello.') + '</div>'));
    }
    for (const s of rows) {
      const unread = s.peer === S.open ? 0 : Number(s.unread || 0);   // the open chat is read by definition
      const lastText = s.group && s.lastName && !s.lastMine ? s.lastName + ': ' + preview(s.last || '') : (s.lastMine ? 'You: ' : '') + preview(s.last || '');
      const item = el('<div class="item' + (S.open === s.peer ? ' on' : '') + '">'
        + '<div class="avatar">' + esc(initial(s.name)) + '</div>'
        + '<div class="meta"><div class="row1"><span class="name">' + esc(s.name || s.peer) + (s.group ? ' <span class="muted small">group</span>' : '') + '</span><span class="time">' + esc(when(s.time)) + '</span></div>'
        + '<div class="row2"><span class="last">' + esc(lastText) + '</span>' + (unread ? '<span class="badge">' + unread + '</span>' : '') + '</div></div></div>');
      item.addEventListener('click', () => go('#chat/' + encodeURIComponent(s.peer)));
      box.appendChild(item);
    }
    if (q) searchMessages(q, box);
  }
  let searchTimer = null;
  $('search').addEventListener('input', () => { S.search = $('search').value; clearTimeout(searchTimer); searchTimer = setTimeout(renderList, 250); });
  async function searchMessages(q, box) {
    try {
      const r = await api('chat.search', { q });
      const msgs = r.messages || [];
      if (!msgs.length || S.search.trim().toLowerCase() !== q) return;
      box.appendChild(el('<div class="sectionTitle">Messages</div>'));
      for (const m of msgs) {
        const item = el('<div class="item"><div class="avatar">' + esc(initial(m.name)) + '</div><div class="meta"><div class="row1"><span class="name">' + esc(m.name) + '</span><span class="time">' + esc(when(m.time)) + '</span></div><div class="row2"><span class="last">' + esc((m.mine ? 'You: ' : '') + m.body) + '</span></div></div></div>');
        item.addEventListener('click', () => go('#chat/' + encodeURIComponent(m.peer)));
        box.appendChild(item);
      }
    } catch (e) { /* search is best-effort */ }
  }

  // ---------- new group ----------
  $('newGroup').addEventListener('click', async () => {
    S.open = null;
    const pane = $('pane'); pane.innerHTML = '';
    let contacts = [];
    try { contacts = (await api('contacts.list', { offset: 0, limit: 200 })).contacts || []; } catch (e) { toast(e.message, 'err'); return; }
    const card = el('<div class="screen"><div class="card"><h2>New group</h2><div class="rowline"><input id="gName" placeholder="Group name"></div><div class="pick" id="gPick"></div><div class="rowline"><button class="primary" id="gCreate">Create group</button><span class="muted small">Up to 12 members including you.</span></div></div></div>');
    const pick = card.querySelector('#gPick');
    if (!contacts.length) pick.appendChild(el('<div class="muted">No contacts yet.</div>'));
    for (const c of contacts) pick.appendChild(el('<label><input type="checkbox" value="' + esc(c.key) + '"> ' + esc(c.name || c.key) + '</label>'));
    card.querySelector('#gCreate').addEventListener('click', async () => {
      const name = card.querySelector('#gName').value.trim();
      const members = [...pick.querySelectorAll('input:checked')].map((i) => i.value);
      if (!name || !members.length) { toast('A name and at least one member', 'err'); return; }
      try { await api('group.create', { name, members }); toast('Group created'); await loadSummaries(); go('#chats'); }
      catch (e) { toast(e.message, 'err'); }
    });
    pane.appendChild(card);
    $('app').classList.add('open');
  });

  // ---------- conversation ----------
  let openSeq = 0;
  async function openChat(peer) {
    const seq = ++openSeq;
    S.open = peer;
    const sum = S.summaries.find((s) => s.peer === peer);
    S.openIsGroup = !!(sum && sum.group);
    S.openName = (sum && sum.name) || peer;
    renderList();
    const pane = $('pane');
    pane.innerHTML = '';
    pane.appendChild(el('<div class="chatHead"><button class="ghost back" id="back">‹</button><div class="avatar">' + esc(initial(S.openName)) + '</div><div style="flex:1;min-width:0"><div class="name">' + esc(S.openName) + '</div><div class="sub" id="chatSub"></div></div><button class="ghost" id="chatInfo" title="Details">ⓘ</button></div>'));
    pane.appendChild(el('<div class="msgs" id="msgs"></div>'));
    pane.appendChild(el('<div class="compose"><input type="file" id="photoFile" accept="image/*" hidden><button class="ghost" id="photoBtn" title="Send a photo">📷</button><textarea id="draft" rows="1" placeholder="Message"></textarea><button class="primary" id="sendBtn">Send</button></div><div class="err" id="sendErr" hidden></div>'));
    $('back').addEventListener('click', () => go('#chats'));
    $('chatInfo').addEventListener('click', () => S.openIsGroup ? groupInfo(peer) : contactInfo(peer));
    $('sendBtn').addEventListener('click', sendDraft);
    $('draft').addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendDraft(); } });
    $('draft').addEventListener('input', () => { const t = $('draft'); t.style.height = 'auto'; t.style.height = Math.min(160, t.scrollHeight) + 'px'; });
    $('photoBtn').addEventListener('click', () => $('photoFile').click());
    $('photoFile').addEventListener('change', () => { const f = $('photoFile').files[0]; if (f) sendPhoto(f); $('photoFile').value = ''; });
    $('draft').focus();
    try {
      const r = await api('chat.conversation', { peer, limit: 100 });
      if (seq !== openSeq) return;
      S.msgs = (r.messages || []).slice().sort((a, b) => Number(a.time) - Number(b.time));
      renderMsgs(true);
      api('chat.markread', { peer }).catch(() => {});
      const s = S.summaries.find((x) => x.peer === peer); if (s) { s.unread = 0; renderList(); }
    } catch (e) { toast(e.message, 'err'); }
    $('msgs').addEventListener('scroll', () => { if ($('msgs').scrollTop < 40) loadOlder(peer); });
  }
  let olderBusy = false, olderDone = false;
  async function loadOlder(peer) {
    if (olderBusy || olderDone || !S.msgs.length) return;
    olderBusy = true;
    try {
      const r = await api('chat.conversation', { peer, limit: 100, before: Number(S.msgs[0].time) });
      const more = (r.messages || []).slice().sort((a, b) => Number(a.time) - Number(b.time));
      if (!more.length) { olderDone = true; return; }
      const box = $('msgs'), before = box.scrollHeight;
      S.msgs = more.concat(S.msgs);
      renderMsgs(false);
      box.scrollTop = box.scrollHeight - before;
    } catch (e) { /* keep what we have */ }
    finally { olderBusy = false; }
  }
  function tick(state) {
    if (state === 'read') return '<span class="tick read">✓✓</span>';
    if (state === 'delivered') return '<span class="tick">✓✓</span>';
    if (state === 'sent') return '<span class="tick">✓</span>';
    if (state === 'failed') return '<span class="tick" title="not delivered">!</span>';
    return '<span class="tick">…</span>';
  }
  function bubbleHtml(e) {
    const mine = !!e.mine;
    const m = parseMedia(e.body || '');
    let inner = '';
    if (S.openIsGroup && !mine && e.sname) inner += '<div class="sname">' + esc(e.sname) + '</div>';
    if (m) {
      const url = mediaUrl(m);
      if (m.mime.startsWith('image/') && url) inner += '<img loading="lazy" alt="photo" src="' + esc(url) + '">';
      else if (m.mime.startsWith('audio/') && url) inner += '<audio controls preload="none" src="' + esc(url) + '"></audio>';
      else if (url) inner += '<a href="' + esc(url) + '" download>' + esc(preview(e.body)) + '</a>';
      else inner += esc(preview(e.body));
      let cap = m.caption; if (m.mime.startsWith('audio/') && cap.indexOf('|') >= 0) cap = cap.slice(0, cap.indexOf('|'));
      if (cap && !m.mime.startsWith('audio/')) inner += '<div class="cap">' + esc(cap) + '</div>';
    } else {
      inner += esc(e.body || '');
    }
    let foot = '<span>' + esc(when(e.time)) + '</span>';
    if (mine) foot += (S.openIsGroup && e.delivered != null ? '<span title="delivered to">' + esc(e.delivered) + '</span>' : '') + tick(e.state);
    return '<div class="msg ' + (mine ? 'mine' : 'theirs') + '" data-id="' + esc(e.id) + '"><div class="bubble">' + inner + '<div class="foot">' + foot + '</div></div></div>';
  }
  function renderMsgs(scrollToEnd) {
    const box = $('msgs'); if (!box) return;
    const atEnd = scrollToEnd || (box.scrollHeight - box.scrollTop - box.clientHeight < 80);
    let html = '', lastDay = '';
    for (const e of S.msgs) {
      const d = dayOf(e.time);
      if (d !== lastDay) { html += '<div class="daysep">' + esc(new Date(Number(e.time)).toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' })) + '</div>'; lastDay = d; }
      html += bubbleHtml(e);
    }
    box.innerHTML = html;
    if (atEnd) box.scrollTop = box.scrollHeight;
  }
  function upsertMsg(e) {
    const i = S.msgs.findIndex((x) => x.id === e.id);
    if (i >= 0) S.msgs[i] = Object.assign({}, S.msgs[i], e); else S.msgs.push(e);
    S.msgs.sort((a, b) => Number(a.time) - Number(b.time));
    renderMsgs(false);
  }
  async function sendDraft() {
    const peer = S.open; if (!peer) return;
    const body = $('draft').value.trim(); if (!body) return;
    $('draft').value = ''; $('draft').style.height = 'auto';
    const local = { id: 'local-' + Date.now() + Math.random().toString(36).slice(2), body, mine: true, time: Date.now(), state: 'sending' };
    S.msgs.push(local); renderMsgs(true);
    try {
      await api('chat.send', { peer, body });
      await reloadOpenTail(peer);
    } catch (e) { local.state = 'failed'; renderMsgs(false); showSendErr(e.message); }
  }
  function showSendErr(msg) { const b = $('sendErr'); if (!b) return; b.textContent = msg; b.hidden = false; setTimeout(() => { b.hidden = true; }, 6000); }
  async function reloadOpenTail(peer) {
    const r = await api('chat.conversation', { peer, limit: 30 });
    const tail = (r.messages || []);
    // Drop optimistic placeholders the account now has for real: same text, or any media of ours
    // newer than the placeholder (a photo's stored body differs from the local data: preview).
    S.msgs = S.msgs.filter((m) => !String(m.id).startsWith('local-')
      || !tail.some((t) => t.mine && (t.body === m.body || (parseMedia(m.body) && parseMedia(t.body) && Number(t.time) >= Number(m.time) - 60000))));
    for (const t of tail) { const i = S.msgs.findIndex((x) => x.id === t.id); if (i >= 0) S.msgs[i] = t; else S.msgs.push(t); }
    S.msgs.sort((a, b) => Number(a.time) - Number(b.time));
    renderMsgs(true);
  }
  async function sendPhoto(file) {
    const peer = S.open; if (!peer) return;
    let blob = file, mime = file.type || 'image/jpeg';
    try {
      const bmp = await createImageBitmap(file);
      const max = 1600, scale = Math.min(1, max / Math.max(bmp.width, bmp.height));
      const c = document.createElement('canvas'); c.width = Math.round(bmp.width * scale); c.height = Math.round(bmp.height * scale);
      c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
      blob = await new Promise((res) => c.toBlob(res, 'image/jpeg', 0.85)); mime = 'image/jpeg';
    } catch (e) { /* not decodable as an image here: send as is */ }
    const bytes = new Uint8Array(await blob.arrayBuffer());
    if (bytes.length > 16 * 1024 * 1024) { showSendErr('Too big: 16 MB is the most a message can carry'); return; }
    const local = { id: 'local-' + Date.now(), body: MEDIA_MARK + mime + 'data:' + mime + ';base64,' + b64(bytes) + '', mine: true, time: Date.now(), state: 'sending' };
    S.msgs.push(local); renderMsgs(true);
    const tid = 'p' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
    const CH = 48 * 1024;
    try {
      for (let off = 0; off < bytes.length; off += CH) {
        const last = off + CH >= bytes.length;
        const p = { tid, off, data: b64(bytes.subarray(off, Math.min(bytes.length, off + CH))), last };
        if (last) { p.peer = peer; p.group = S.openIsGroup; p.mime = mime; p.caption = ''; }
        await api('media.up', p);
      }
      toast('Photo sent - publishing to the relays');
      setTimeout(() => reloadOpenTail(peer).catch(() => {}), 2500);
      setTimeout(() => reloadOpenTail(peer).catch(() => {}), 12000);
    } catch (e) { local.state = 'failed'; renderMsgs(false); showSendErr(e.message); }
  }
  function b64(u8) { let s = ''; for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000)); return btoa(s); }

  async function contactInfo(key) {
    let c;
    try { c = await api('contacts.info', { key }); } catch (e) { toast(e.message, 'err'); return; }
    const pane = $('pane'); pane.innerHTML = '';
    const card = el('<div class="screen"><button class="ghost" id="ciBack">‹ Back to the chat</button><div class="card"><h2>Contact</h2><div class="kv">'
      + '<span class="k">Name</span><span class="v"><input id="ciName" value="' + esc(c.name || '') + '"> <button id="ciRename">Rename</button></span>'
      + '<span class="k">Key</span><span class="v mono">' + esc(c.key || key) + '</span>'
      + '<span class="k">Address</span><span class="v mono">' + esc(c.address || '') + '</span>'
      + '<span class="k">Last seen</span><span class="v">' + esc(when(c.lastSeen)) + '</span>'
      + '</div><div class="rowline"><button id="ciResolve">Re-resolve their address</button><button class="danger" id="ciRemove">Remove contact</button></div></div></div>');
    card.querySelector('#ciBack').addEventListener('click', () => openChat(key));
    card.querySelector('#ciRename').addEventListener('click', async () => { try { await api('contacts.rename', { key, name: card.querySelector('#ciName').value.trim() }); toast('Renamed'); await loadSummaries(); } catch (e) { toast(e.message, 'err'); } });
    card.querySelector('#ciResolve').addEventListener('click', async () => { try { const r = await api('contacts.resolve', { key }); toast(r.updated ? 'Address refreshed' : 'No fresher record'); } catch (e) { toast(e.message, 'err'); } });
    card.querySelector('#ciRemove').addEventListener('click', async () => { if (!confirm('Remove this contact?')) return; try { await api('contacts.remove', { key }); toast('Removed'); await loadSummaries(); go('#chats'); } catch (e) { toast(e.message, 'err'); } });
    pane.appendChild(card);
  }
  async function groupInfo(id) {
    let g;
    try { g = await api('group.info', { id }); } catch (e) { toast(e.message, 'err'); return; }
    const pane = $('pane'); pane.innerHTML = '';
    const rows = (g.members || []).map((m) => '<tr><td>' + esc(m.name || '') + (m.me ? ' (you)' : '') + (m.admin ? ' · admin' : '') + '</td><td class="mono">' + esc(m.key) + '</td></tr>').join('');
    const card = el('<div class="screen"><button class="ghost" id="giBack">‹ Back to the chat</button><div class="card"><h2>Group</h2><div class="rowline"><input id="giName" value="' + esc(g.name || '') + '"' + (g.iAmAdmin ? '' : ' disabled') + '>' + (g.iAmAdmin ? '<button id="giRename">Rename</button>' : '<span class="muted small">Only an admin can change the group.</span>') + '</div><table class="t">' + rows + '</table></div></div>');
    card.querySelector('#giBack').addEventListener('click', () => openChat(id));
    const rn = card.querySelector('#giRename');
    if (rn) rn.addEventListener('click', async () => { try { await api('group.update', { id, name: card.querySelector('#giName').value.trim() }); toast('Renamed'); await loadSummaries(); } catch (e) { toast(e.message, 'err'); } });
    pane.appendChild(card);
  }

  // ---------- contacts ----------
  async function renderContacts() {
    const pane = $('pane'); pane.innerHTML = '';
    const screen = el('<div class="screen"></div>');
    pane.appendChild(screen);
    const add = el('<div class="card"><h2>Add a contact</h2><div class="rowline"><input id="addAddr" placeholder="Paste their address (MAX#… or Mx…)"><button class="primary" id="addBtn">Add</button></div><div class="muted small">They get an introduction; the chat opens once they accept.</div></div>');
    add.querySelector('#addBtn').addEventListener('click', async () => {
      const address = add.querySelector('#addAddr').value.trim(); if (!address) return;
      try { await api('contacts.add', { address }); toast('Introduction sent'); add.querySelector('#addAddr').value = ''; setTimeout(renderContacts, 1500); } catch (e) { toast(e.message, 'err'); }
    });
    screen.appendChild(add);
    const mine = el('<div class="card"><h2>My address</h2><div class="muted small">Share it; it never changes.</div><div class="qr" id="myQr"></div><div class="mono whole" id="myAddr">' + esc(S.me.permanent) + '</div><div class="rowline"><button id="copyAddr">Copy address</button></div></div>');
    mine.querySelector('#copyAddr').addEventListener('click', () => copy(S.me.permanent));
    screen.appendChild(mine);
    drawQr(mine.querySelector('#myQr'), S.me.permanent);
    const list = el('<div class="card"><h2>Contacts</h2><table class="t" id="cTable"></table></div>');
    screen.appendChild(list);
    try {
      const r = await api('contacts.list', { offset: 0, limit: 200 });
      S.contacts = r.contacts || [];
      const t = list.querySelector('#cTable');
      if (!S.contacts.length) t.innerHTML = '<tr><td class="muted">No contacts yet.</td></tr>';
      for (const c of S.contacts) {
        const tr = el('<tr><td>' + esc(c.name || '(no name)') + '<br><span class="muted small">' + esc(when(c.lastSeen)) + '</span></td><td><span class="mono">' + esc(c.key) + '</span><div class="rowline"><button class="small" data-act="chat">Chat</button><button class="small" data-act="info">Details</button></div></td></tr>');
        tr.querySelector('[data-act=chat]').addEventListener('click', () => go('#chat/' + encodeURIComponent(c.key)));
        tr.querySelector('[data-act=info]').addEventListener('click', () => { S.open = c.key; contactInfo(c.key); });
        t.appendChild(tr);
      }
    } catch (e) { toast(e.message, 'err'); }
  }
  function drawQr(host, text) {
    if (!text) { host.textContent = ''; return; }
    try { const q = qrcode(0, 'M'); q.addData(text); q.make(); host.innerHTML = q.createImgTag(3, 0); } catch (e) { host.textContent = ''; }
  }
  async function copy(text) {
    try { await navigator.clipboard.writeText(text); toast('Copied'); }
    catch (e) { const ta = document.createElement('textarea'); ta.value = text; document.body.appendChild(ta); ta.select(); document.execCommand('copy'); ta.remove(); toast('Copied'); }
  }

  // ---------- devices ----------
  async function renderDevices() {
    const pane = $('pane'); pane.innerHTML = '';
    const screen = el('<div class="screen"></div>'); pane.appendChild(screen);
    const pair = el('<div class="card"><h2>Pair a phone</h2><p class="muted small" id="invText">No pairing code is outstanding.</p><div class="qr" id="invQr"></div><div class="mono whole" id="invTxt"></div><div class="rowline"><button class="primary" id="newCode">New pairing code</button><button id="copyInv" hidden>Copy invite</button></div></div>');
    screen.appendChild(pair);
    const devs = el('<div class="card"><h2>Devices</h2><table class="t" id="devTable"></table></div>');
    screen.appendChild(devs);
    async function refreshInvite() {
      try {
        const inv = await apiGet('invite');
        if (inv.invite) {
          pair.querySelector('#invText').textContent = 'Scan this with the Parlons app on your phone, or paste the text (the code half works once):';
          drawQr(pair.querySelector('#invQr'), inv.invite);
          pair.querySelector('#invTxt').textContent = inv.invite;
          pair.querySelector('#copyInv').hidden = false;
          pair.querySelector('#copyInv').onclick = () => copy(inv.invite);
        } else {
          pair.querySelector('#invText').textContent = 'No pairing code is outstanding.';
          pair.querySelector('#invQr').innerHTML = ''; pair.querySelector('#invTxt').textContent = ''; pair.querySelector('#copyInv').hidden = true;
        }
      } catch (e) { /* shown on the next refresh */ }
    }
    async function refreshDevices() {
      try {
        const d = await api('pair.list');
        const t = devs.querySelector('#devTable'); t.innerHTML = '';
        for (const dev of (d.authorized || [])) {
          const tr = el('<tr><td>' + (dev.local ? 'this computer' : 'paired') + '</td><td>' + esc(dev.label) + '<br><span class="mono">' + esc(dev.key) + '</span><div class="rowline"><button class="danger small" data-k="' + esc(dev.key) + '">Revoke</button></div></td></tr>');
          tr.querySelector('button').addEventListener('click', async () => { if (!confirm('Revoke this device? It loses access to the account.')) return; try { await api('pair.revoke', { device: dev.key }); toast('Revoked'); refreshDevices(); } catch (e) { toast(e.message, 'err'); } });
          t.appendChild(tr);
        }
        for (const k of (d.pending || [])) {
          const tr = el('<tr><td>waiting</td><td><span class="mono">' + esc(k) + '</span><div class="rowline"><button class="primary small">Approve</button></div></td></tr>');
          tr.querySelector('button').addEventListener('click', async () => { try { await api('pair.approve', { device: k }); toast('Approved'); refreshDevices(); } catch (e) { toast(e.message, 'err'); } });
          t.appendChild(tr);
        }
        if (!t.children.length) t.innerHTML = '<tr><td class="muted">none</td></tr>';
      } catch (e) { toast(e.message, 'err'); }
    }
    pair.querySelector('#newCode').addEventListener('click', async () => { try { await api('pair.newcode'); toast('New code minted'); setTimeout(refreshInvite, 900); } catch (e) { toast(e.message, 'err'); } });
    refreshInvite(); refreshDevices();
  }

  // ---------- node ----------
  async function renderNode() {
    const pane = $('pane'); pane.innerHTML = '';
    const screen = el('<div class="screen"></div>'); pane.appendChild(screen);
    let st = {}, fig = {};
    try { st = await api('node.status'); } catch (e) { toast(e.message, 'err'); }
    try { fig = await api('node.figures'); } catch (e) { /* cloud without figures */ }
    const up = Number(st.uptime || 0), h = Math.floor(up / 3600000), m = Math.floor((up % 3600000) / 60000);
    screen.appendChild(el('<div class="card"><h2>Account</h2><div class="kv">'
      + '<span class="k">Name</span><span class="v">' + esc(st.name) + '</span>'
      + '<span class="k">Version</span><span class="v">' + esc(st.version) + '</span>'
      + '<span class="k">Up</span><span class="v">' + h + ' h ' + m + ' min</span>'
      + '<span class="k">Relays attached</span><span class="v">' + esc(st.hosts) + '</span>'
      + '<span class="k">Own relay</span><span class="v">' + (st.relayOn ? 'on' : 'off') + (fig.ownRelay ? ' · ' + esc(fig.ownRelay) + (fig.ownRelayVerified ? ' ✓' : '') : '') + '</span>'
      + '<span class="k">Mesh peers</span><span class="v">' + esc(st.meshPeers) + '</span>'
      + '<span class="k">Paired devices</span><span class="v">' + esc(st.pairedDevices) + '</span>'
      + '<span class="k">Permanent address</span><span class="v mono">' + esc(st.permanent) + '</span>'
      + (fig.directAddress ? '<span class="k">Direct address</span><span class="v mono">' + esc(fig.directAddress) + '</span>' : '')
      + '<span class="k">Mailbox / outbox</span><span class="v">' + esc(fig.mailboxHeld == null ? '' : fig.mailboxHeld) + ' / ' + esc(fig.outbox == null ? '' : fig.outbox) + '</span>'
      + '</div></div>'));
    const hosts = el('<div class="card"><h2>Relays</h2><table class="t" id="hostTable"></table><div class="rowline"><input id="hostAdd" placeholder="host:port to attach, or a relay QR text"><button id="hostAddBtn">Add</button></div><label class="rowline"><input type="checkbox" id="builtin"' + (fig.builtin ? ' checked' : '') + '> Use the built-in relay list as one seed source</label></div>');
    const ht = hosts.querySelector('#hostTable');
    for (const hh of (fig.hosts || [])) {
      const tr = el('<tr><td>' + (hh.connected ? '● connected' : '○ not attached') + '</td><td><span class="mono">' + esc(hh.host) + '</span>' + (hh.builtin ? ' <span class="muted small">built-in</span>' : ' <button class="small danger" data-h="' + esc(hh.host) + '">Remove</button>') + '</td></tr>');
      const rb = tr.querySelector('button'); if (rb) rb.addEventListener('click', async () => { try { await api('node.hosts', { remove: hh.host }); toast('Detached'); renderNode(); } catch (e) { toast(e.message, 'err'); } });
      ht.appendChild(tr);
    }
    hosts.querySelector('#hostAddBtn').addEventListener('click', async () => { const v = hosts.querySelector('#hostAdd').value.trim(); if (!v) return; try { await api('node.hosts', { add: v }); toast('Connecting…'); setTimeout(renderNode, 1500); } catch (e) { toast(e.message, 'err'); } });
    hosts.querySelector('#builtin').addEventListener('change', async (ev) => { try { await api('node.hosts', { builtin: ev.target.checked }); toast(ev.target.checked ? 'Built-in list on' : 'Built-in list off'); } catch (e) { toast(e.message, 'err'); renderNode(); } });
    screen.appendChild(hosts);
    const mls = el('<div class="card"><h2>Directory anchor</h2><div class="muted small">Where your permanent address resolves. Pinning keeps it on one relay you trust; clear lets the account pick.</div><div class="rowline"><button id="mlsPin">Pin the best attached relay</button><button id="mlsClear">Clear pin</button><button id="mlsRepub">Republish now</button></div><div class="mono whole" id="mlsInfo"></div></div>');
    async function mlsDo(action) { try { const r = await api('node.mls', { action }); mls.querySelector('#mlsInfo').textContent = (r.pinned ? 'pinned: ' : 'auto: ') + (r.mls || ''); toast('Done'); } catch (e) { toast(e.message, 'err'); } }
    mls.querySelector('#mlsPin').addEventListener('click', () => mlsDo('pin'));
    mls.querySelector('#mlsClear').addEventListener('click', () => mlsDo('clear'));
    mls.querySelector('#mlsRepub').addEventListener('click', () => mlsDo('republish'));
    screen.appendChild(mls);
    const log = el('<div class="card"><h2>Log</h2><div class="log" id="nodeLog">…</div><div class="rowline"><button id="logRefresh">Refresh</button><button id="logClear">Clear</button></div></div>');
    async function loadLog(clear) { try { const r = await api('node.log', clear ? { clear: true } : {}); log.querySelector('#nodeLog').textContent = (r.lines || []).join('\n') || '(empty)'; } catch (e) { toast(e.message, 'err'); } }
    log.querySelector('#logRefresh').addEventListener('click', () => loadLog(false));
    log.querySelector('#logClear').addEventListener('click', () => loadLog(true));
    screen.appendChild(log);
    loadLog(false);
  }

  // ---------- settings ----------
  async function renderSettings() {
    const pane = $('pane'); pane.innerHTML = '';
    const screen = el('<div class="screen"></div>'); pane.appendChild(screen);
    let s = {};
    try { s = await api('settings.get'); } catch (e) { /* defaults */ }
    const name = el('<div class="card"><h2>Your name</h2><div class="muted small">What your contacts see.</div><div class="rowline"><input id="setName" value="' + esc(S.me.name) + '"><button class="primary" id="saveName">Save</button></div></div>');
    name.querySelector('#saveName').addEventListener('click', async () => { const v = name.querySelector('#setName').value.trim(); if (!v) return; try { await api('identity.setname', { name: v }); S.me.name = v; $('meName').textContent = v; toast('Saved'); } catch (e) { toast(e.message, 'err'); } });
    screen.appendChild(name);
    const rr = el('<div class="card"><h2>Privacy</h2><label class="rowline"><input type="checkbox" id="rr"' + (s.readReceipts ? ' checked' : '') + '> Send read receipts (your contacts see when you have read their messages)</label></div>');
    rr.querySelector('#rr').addEventListener('change', async (ev) => { try { await api('settings.set', { readReceipts: ev.target.checked }); toast('Saved'); } catch (e) { toast(e.message, 'err'); } });
    screen.appendChild(rr);
    const ab = el('<div class="card"><h2>This panel</h2><div class="muted small">Signed in as this computer, a local device of the account. The seed, the wallet and the node console stay on your paired phones and the command line.</div><div class="rowline"><button id="openBrowser">Open in a browser</button><button id="signOut">Sign out</button></div><div class="mono whole" id="ticketOut"></div></div>');
    ab.querySelector('#openBrowser').addEventListener('click', async () => { try { const r = await api('ticket'); ab.querySelector('#ticketOut').textContent = r.url; try { window.open(r.url, '_blank'); } catch (e) {} } catch (e) { toast(e.message, 'err'); } });
    ab.querySelector('#signOut').addEventListener('click', async () => { try { await api('logout'); } catch (e) {} location.reload(); });
    screen.appendChild(ab);
  }

  // ---------- live events ----------
  let es = null, reconnectTimer = null;
  function listen() {
    if (es) { try { es.close(); } catch (e) {} }
    es = new EventSource('/events');
    es.addEventListener('hello', () => { setState('connected'); catchUp(); });
    es.addEventListener('push', (ev) => {
      let e; try { e = JSON.parse(ev.data); } catch (x) { return; }
      handleEvent(e);
    });
    es.onerror = () => { setState('reconnecting…', true); };
  }
  async function catchUp() {
    if (!S.lastEvent) { await loadSummaries().catch(() => {}); return; }
    try {
      const r = await api('chat.since', { cursor: S.lastEvent, limit: 100 });
      for (const e of (r.entries || r.messages || [])) if (S.open && (e.peer === S.open || e.groupId === S.open)) upsertMsg(e);
    } catch (e) { /* summaries below cover it */ }
    await loadSummaries().catch(() => {});
  }
  function handleEvent(e) {
    if (e.time) S.lastEvent = Math.max(S.lastEvent, Number(e.time));
    if (e.type === 'message') {
      const conv = e.peer;
      if (S.open === conv) {
        upsertMsg({ id: e.id, body: e.body, mine: false, sender: e.sender, sname: e.sname, time: e.time, state: '' });
        api('chat.markread', { peer: conv }).catch(() => {});
      }
      loadSummaries().catch(() => {});
      if (document.hidden || S.open !== conv) notify(e);
    } else if (e.type === 'state') {
      if (S.open === e.peer) { const m = S.msgs.find((x) => x.id === e.id); if (m) { m.state = e.state; renderMsgs(false); } }
    } else if (e.type === 'call') {
      if (e.kind === 'offer') showBanner((e.name || 'Someone') + ' is calling - answer on your phone', 30000);
      else if (e.kind === 'taken' || e.kind === 'bye') hideBanner();
    } else if (e.type === 'walletfail') {
      toast('Payment failed: ' + (e.error || ''), 'err');
    }
  }
  function showBanner(text, ms) { const b = $('banner'); b.textContent = text; b.hidden = false; if (ms) setTimeout(hideBanner, ms); }
  function hideBanner() { $('banner').hidden = true; }
  function notify(e) {
    if (!('Notification' in window) || Notification.permission !== 'granted') return;
    try { const n = new Notification(e.name || 'Parlons', { body: preview(e.body || ''), tag: e.peer }); n.onclick = () => { window.focus(); go('#chat/' + encodeURIComponent(e.peer)); }; } catch (x) {}
  }

  // ---------- boot ----------
  async function boot() {
    try {
      const p = await api('ping');
      S.me = { name: p.name || '', permanent: p.permanent || '', primary: p.primary || '' };
      $('meName').textContent = S.me.name;
      setState('connected');
    } catch (e) { setState('cannot reach the account', true); return; }
    await loadSummaries().catch((e) => toast(e.message, 'err'));
    if ('Notification' in window && Notification.permission === 'default') { try { Notification.requestPermission(); } catch (e) {} }
    listen();
    render();
  }
  boot();
})();
