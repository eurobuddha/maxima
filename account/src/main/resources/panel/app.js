// Parlons panel - the Parlons phone app, rendered as it is, over the account's local API.
// Left: the phone (home app bar, tab strip, pages). Right (wide windows): the chat screen. Every call is
// POST /api/<method> with the session cookie; live events on /events (SSE). No framework, no inline script.
(function () {
  'use strict';

  // ---------- helpers ----------
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const el = (html) => { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstElementChild; };
  const MEDIA_MARK = '\u0001m\u0001';   // core ChatMedia: SOH-fenced 'm', then mime SOH ref SOH caption
  const ic = window.icon;

  async function api(method, body) {
    const r = await fetch('/api/' + method, { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
    if (r.status === 401) { setState('signed out', 'bad'); throw new Error('Signed out - open the panel with a fresh link'); }
    const j = await r.json().catch(() => ({ ok: false, error: 'bad reply' }));
    if (j && j.ok === false) throw new Error(j.error || method + ' failed');
    return j;
  }
  async function apiGet(name) {
    const r = await fetch('/api/' + name, { credentials: 'same-origin' });
    if (r.status === 401) { setState('signed out', 'bad'); throw new Error('Signed out'); }
    return r.json();
  }
  let toastTimer = null;
  function toast(msg, kind) {
    const t = $('toast'); t.textContent = msg; t.className = 'toast' + (kind === 'err' ? ' err' : ''); t.hidden = false;
    clearTimeout(toastTimer); toastTimer = setTimeout(() => { t.hidden = true; }, kind === 'err' ? 5000 : 2400);
  }
  function setState(text, kind) {
    $('state').textContent = text;
    const p = $('state').parentElement; p.className = 'hpill' + (kind === 'ok' ? ' ok' : kind === 'bad' ? ' bad' : '');
  }
  const pad2 = (n) => (n < 10 ? '0' : '') + n;
  function hhmm(ms) { const d = new Date(Number(ms)); return pad2(d.getHours()) + ':' + pad2(d.getMinutes()); }
  const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const DAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
  function dayStart(ms) { const d = new Date(Number(ms)); d.setHours(0, 0, 0, 0); return d.getTime(); }
  // ChatActivity.dayLabel: Today / Yesterday / weekday (< 7 days) / "7 Sep 2026"
  function dayLabel(ms) {
    const days = Math.round((dayStart(Date.now()) - dayStart(ms)) / 86400000);
    if (days === 0) return 'Today';
    if (days === 1) return 'Yesterday';
    const d = new Date(Number(ms));
    if (days > 1 && days < 7) return DAYS[d.getDay()];
    return d.getDate() + ' ' + MONTHS[d.getMonth()] + ' ' + d.getFullYear();
  }
  // the list's time column: HH:mm today, else "30 Aug"
  function listTime(ms) {
    if (!ms) return '';
    const d = new Date(Number(ms));
    return dayStart(ms) === dayStart(Date.now()) ? hhmm(ms) : d.getDate() + ' ' + MONTHS[d.getMonth()];
  }
  // Presence.of: online < 30 min, then last seen Nm / Nh / Nd ago
  function presence(lastSeen) {
    if (!lastSeen) return '';
    const d = Date.now() - Number(lastSeen);
    if (d < 30 * 60000) return 'online';
    const m = Math.floor(d / 60000); if (m < 60) return 'last seen ' + m + 'm ago';
    const h = Math.floor(m / 60); if (h < 24) return 'last seen ' + h + 'h ago';
    return 'last seen ' + Math.floor(h / 24) + 'd ago';
  }
  // Avatars.colour, ported exactly: Java String.hashCode of the WHOLE key → floorMod 30 → muted HSL
  function javaHash(s) { let h = 0; for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0; return h; }
  function avatarColour(key) {
    const v = key ? javaHash(String(key)) : 0;
    const idx = ((v % 30) + 30) % 30;
    const hue = idx * 12, sat = 0.32 + (idx % 3) * 0.03, light = 0.42 + (idx % 2) * 0.03;
    return 'hsl(' + hue + ',' + Math.round(sat * 100) + '%,' + Math.round(light * 100) + '%)';
  }
  function initial(name) { const s = String(name || '').trim(); return s ? s[0].toUpperCase() : '?'; }
  function avatar(key, name, size) { return '<div class="av ' + (size || 'l') + '" style="background:' + avatarColour(key) + '">' + esc(initial(name)) + '</div>'; }

  // ---------- media bodies (core ChatMedia) ----------
  function parseMedia(body) {
    if (!body || !body.startsWith(MEDIA_MARK)) return null;
    const rest = body.slice(MEDIA_MARK.length);
    const a = rest.indexOf('\u0001'); if (a < 0) return null;
    const rest2 = rest.slice(a + 1);
    const b = rest2.indexOf('\u0001'); if (b < 0) return null;
    return { mime: rest.slice(0, a), ref: rest2.slice(0, b), caption: rest2.slice(b + 1) };
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
  const S = { me: { name: '', permanent: '', primary: '' }, version: '', summaries: [], contacts: [], open: null, openIsGroup: false, openName: '', msgs: [], route: 'chats', lastEvent: 0, search: '', showSearch: false };

  // ---------- theme (the app's theme button: system → light → dark) ----------
  function applyTheme() {
    const t = localStorage.getItem('parlons.theme') || 'system';
    if (t === 'system') document.documentElement.removeAttribute('data-theme'); else document.documentElement.setAttribute('data-theme', t);
  }
  $('btnTheme').innerHTML = ic('theme');
  $('btnTheme').addEventListener('click', () => {
    const t = localStorage.getItem('parlons.theme') || 'system';
    const next = t === 'system' ? 'light' : t === 'light' ? 'dark' : 'system';
    localStorage.setItem('parlons.theme', next); applyTheme(); toast(next === 'system' ? 'Theme follows the system' : next === 'light' ? 'Light theme' : 'Dark theme');
  });
  applyTheme();
  $('btnSearch').innerHTML = ic('search');
  $('btnSearch').addEventListener('click', () => { S.showSearch = !S.showSearch; if (S.route !== 'chats') go('#chats'); else renderChats(); setTimeout(() => { const f = $('search'); if (f) f.focus(); }, 50); });
  $('btnMore').innerHTML = ic('more');
  $('btnMore').addEventListener('click', () => sheet('<div class="h">Parlons</div><div class="sub">Signed in as this computer, a local device of the account.</div>'
    + '<button class="btn ghost full" id="shBrowser">Open in a browser</button><button class="btn ghost full" id="shOut">Sign out</button>', (sh) => {
      sh.querySelector('#shBrowser').addEventListener('click', async () => { try { const r = await api('ticket'); window.open(r.url, '_blank'); } catch (e) { toast(e.message, 'err'); } closeSheet(); });
      sh.querySelector('#shOut').addEventListener('click', async () => { try { await api('logout'); } catch (e) {} location.reload(); });
    }));

  // ---------- bottom sheets (the app's sheet vocabulary) ----------
  function sheet(html, wire) {
    closeSheet();
    const back = el('<div class="sheetback" id="sheet"><div class="sheet"><div class="grip"></div>' + html + '</div></div>');
    back.addEventListener('click', (e) => { if (e.target === back) closeSheet(); });
    document.body.appendChild(back);
    if (wire) wire(back);
    return back;
  }
  function closeSheet() { const s = $('sheet'); if (s) s.remove(); }

  // ---------- routing ----------
  function go(hash) { location.hash = hash; }
  window.addEventListener('hashchange', render);
  document.querySelectorAll('.tabs button').forEach((b) => b.addEventListener('click', () => go('#' + b.dataset.route)));
  function render() {
    const h = location.hash.replace(/^#/, '') || 'chats';
    const [route, arg] = h.split('/');
    if (route === 'chat' && arg) { openChat(decodeURIComponent(arg)); if (S.route !== 'chats') { S.route = 'chats'; renderChats(); } setTab('chats'); return; }
    S.route = route; setTab(route);
    $('app').classList.remove('chat');
    if (route === 'contacts') renderContacts();
    else if (route === 'devices') renderDevices();
    else if (route === 'node') renderNode();
    else if (route === 'settings') renderSettings();
    else renderChats();
  }
  function setTab(route) { document.querySelectorAll('.tabs button').forEach((b) => b.classList.toggle('on', b.dataset.route === route)); }

  // ---------- Chats page ----------
  async function loadSummaries() {
    const r = await api('chat.summaries', { offset: 0, limit: 100 });
    S.summaries = (r.summaries || []).sort((a, b) => Number(b.time || 0) - Number(a.time || 0));
    if (S.route === 'chats') renderChats();
  }
  function renderChats() {
    const page = $('page'); page.innerHTML = '';
    if (S.showSearch || S.search) {
      const f = el('<input class="search" id="search" type="search" placeholder="Search chats" autocomplete="off" value="' + esc(S.search) + '">');
      let t = null; f.addEventListener('input', () => { S.search = f.value; clearTimeout(t); t = setTimeout(renderChatsList, 200); });
      page.appendChild(f);
    }
    page.appendChild(el('<div class="convs" id="convs"></div>'));
    const fab = el('<button class="fab" id="fab" title="New group">' + ic('newchat') + '</button>');
    fab.addEventListener('click', newGroup);
    page.appendChild(fab);
    renderChatsList();
  }
  function renderChatsList() {
    const box = $('convs'); if (!box) return;
    box.innerHTML = '';
    const q = S.search.trim().toLowerCase();
    let rows = S.summaries;
    if (q) rows = rows.filter((s) => (s.name || '').toLowerCase().includes(q) || (s.last || '').toLowerCase().includes(q));
    if (!rows.length) box.appendChild(el('<div class="emptyPage">' + (q ? 'Nothing matches.' : 'No chats yet.<br>Add a contact and say hello.') + '</div>'));
    for (const s of rows) {
      const unread = s.peer === S.open ? 0 : Number(s.unread || 0);
      const last = s.last ? (s.group && s.lastName && !s.lastMine ? s.lastName + ': ' : (s.lastMine ? 'You: ' : '')) + preview(s.last) : 'no messages yet';
      const row = el('<div class="conv' + (S.open === s.peer ? ' on' : '') + '">' + avatar(s.peer, s.name, 'l')
        + '<div class="mid"><div class="name">' + esc(s.name || s.peer) + '</div><div class="prev">' + esc(last) + '</div></div>'
        + '<div class="right"><div class="time">' + esc(listTime(s.time)) + '</div>' + (unread ? '<div class="badge">' + unread + '</div>' : '') + '</div></div>');
      row.addEventListener('click', () => go('#chat/' + encodeURIComponent(s.peer)));
      box.appendChild(row);
    }
    if (q) searchMessages(q, box);
  }
  async function searchMessages(q, box) {
    try {
      const r = await api('chat.search', { q });
      if (S.search.trim().toLowerCase() !== q) return;
      for (const m of (r.messages || [])) {
        const row = el('<div class="conv">' + avatar(m.peer, m.name, 'l') + '<div class="mid"><div class="name">' + esc(m.name) + '</div><div class="prev">' + esc((m.mine ? 'You: ' : '') + m.body) + '</div></div><div class="right"><div class="time">' + esc(listTime(m.time)) + '</div></div></div>');
        row.addEventListener('click', () => go('#chat/' + encodeURIComponent(m.peer)));
        box.appendChild(row);
      }
    } catch (e) { /* best effort */ }
  }
  async function newGroup() {
    let contacts = [];
    try { contacts = (await api('contacts.list', { offset: 0, limit: 200 })).contacts || []; } catch (e) { toast(e.message, 'err'); return; }
    const picks = contacts.map((c) => '<label><input type="checkbox" value="' + esc(c.key) + '"> ' + esc(c.name || c.key) + '</label>').join('') || '<div class="sub">No contacts yet.</div>';
    sheet('<div class="h">New group</div><div class="sub">Up to 12 members including you.</div><div class="frow"><input class="field" id="gName" placeholder="Group name"></div><div class="pick">' + picks + '</div><button class="btn full" id="gCreate">Create group</button>', (sh) => {
      sh.querySelector('#gCreate').addEventListener('click', async () => {
        const name = sh.querySelector('#gName').value.trim(), members = [...sh.querySelectorAll('input:checked')].map((i) => i.value);
        if (!name || !members.length) { toast('A name and at least one member', 'err'); return; }
        try { await api('group.create', { name, members }); closeSheet(); toast('Group created'); await loadSummaries(); } catch (e) { toast(e.message, 'err'); }
      });
    });
  }

  // ---------- the chat screen ----------
  let openSeq = 0, olderBusy = false, olderDone = false;
  async function openChat(peer) {
    const seq = ++openSeq;
    S.open = peer; olderDone = false;
    const sum = S.summaries.find((s) => s.peer === peer);
    S.openIsGroup = !!(sum && sum.group);
    S.openName = (sum && sum.name) || peer;
    renderChatsList();
    $('app').classList.add('chat');
    const pane = $('chatpane'); pane.innerHTML = '';
    pane.appendChild(el('<div class="cbar"><button class="ibtn back" id="back">' + ic('back') + '</button>' + avatar(peer, S.openName, 'm')
      + '<div class="titles"><div class="ctitle">' + esc(S.openName) + '</div><div class="csub" id="csub"></div></div>'
      + '<button class="ibtn" id="cTheme">' + ic('theme') + '</button><button class="ibtn" id="cVideo">' + ic('videocall') + '</button><button class="ibtn" id="cCall">' + ic('call') + '</button><button class="ibtn narrow" id="cMore">' + ic('more') + '</button></div>'));
    pane.appendChild(el('<div class="msgs" id="msgs"></div>'));
    pane.appendChild(el('<div class="composer"><div class="ipill"><button class="ibtn" id="emojiBtn" title="Emoji">' + ic('emoji') + '</button><textarea id="draft" rows="1" placeholder="Message"></textarea>'
      + '<input type="file" id="attachFile" hidden><button class="ibtn" id="attachBtn" title="Attach">' + ic('attach') + '</button><input type="file" id="photoFile" accept="image/*" hidden><button class="ibtn" id="photoBtn" title="Photo">' + ic('camera') + '</button></div>'
      + '<button class="sendbtn" id="sendBtn" title="Send">' + ic('send') + '</button></div><div class="senderr" id="sendErr" hidden></div>'));
    $('back').addEventListener('click', () => { S.open = null; $('app').classList.remove('chat'); renderChatsList(); history.replaceState(null, '', '#chats'); });
    $('cTheme').addEventListener('click', () => $('btnTheme').click());
    $('cVideo').addEventListener('click', () => showBanner('Video calls ring on your phone - open Parlons there to call ' + S.openName, 5000));
    $('cCall').addEventListener('click', () => showBanner('Calls ring on your phone - open Parlons there to call ' + S.openName, 5000));
    $('cMore').addEventListener('click', () => S.openIsGroup ? groupInfo(peer) : contactInfo(peer));
    $('sendBtn').addEventListener('click', sendDraft);
    $('emojiBtn').addEventListener('click', () => { const d = $('draft'); d.focus(); });
    $('draft').addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendDraft(); } });
    $('draft').addEventListener('input', () => { const t = $('draft'); t.style.height = 'auto'; t.style.height = Math.min(140, t.scrollHeight) + 'px'; });
    $('photoBtn').addEventListener('click', () => $('photoFile').click());
    $('photoFile').addEventListener('change', () => { const f = $('photoFile').files[0]; if (f) sendFile(f, true); $('photoFile').value = ''; });
    $('attachBtn').addEventListener('click', () => $('attachFile').click());
    $('attachFile').addEventListener('change', () => { const f = $('attachFile').files[0]; if (f) sendFile(f, false); $('attachFile').value = ''; });
    $('draft').focus();
    subtitle(peer);
    try {
      const r = await api('chat.conversation', { peer, limit: 100 });
      if (seq !== openSeq) return;
      S.msgs = (r.messages || []).slice().sort((a, b) => Number(a.time) - Number(b.time));
      renderMsgs(true);
      api('chat.markread', { peer }).catch(() => {});
      const s = S.summaries.find((x) => x.peer === peer); if (s) { s.unread = 0; renderChatsList(); }
    } catch (e) { toast(e.message, 'err'); }
    const box = $('msgs');
    box.addEventListener('scroll', () => { if (box.scrollTop < 40) loadOlder(peer); toBottomBtn(); });
  }
  async function subtitle(peer) {
    const sub = $('csub'); if (!sub) return;
    try {
      if (S.openIsGroup) { const g = await api('group.info', { id: peer }); sub.textContent = (g.members || []).length + ' member(s)' + (g.iAmAdmin ? '  ·  you are an admin' : ''); }
      else { const c = await api('contacts.info', { key: peer }); const p = presence(c.lastSeen); sub.textContent = p || (c.address ? 'not reached yet' : 'no address yet'); sub.classList.toggle('online', p === 'online'); }
    } catch (e) { sub.textContent = S.openIsGroup ? '' : 'not in your contacts'; }
  }
  function toBottomBtn() {
    const box = $('msgs'); if (!box) return;
    const far = box.scrollHeight - box.scrollTop - box.clientHeight > 300;
    let b = $('toBottom');
    if (far && !b) { b = el('<button class="tobottom" id="toBottom">' + ic('arrowdown') + '</button>'); b.addEventListener('click', () => { box.scrollTop = box.scrollHeight; }); $('chatpane').appendChild(b); }
    if (!far && b) b.remove();
  }
  async function loadOlder(peer) {
    if (olderBusy || olderDone || !S.msgs.length) return;
    olderBusy = true;
    try {
      const r = await api('chat.conversation', { peer, limit: 100, before: Number(S.msgs[0].time) });
      const more = (r.messages || []).slice().sort((a, b) => Number(a.time) - Number(b.time));
      if (!more.length) { olderDone = true; return; }
      const box = $('msgs'), before = box.scrollHeight;
      S.msgs = more.concat(S.msgs); renderMsgs(false); box.scrollTop = box.scrollHeight - before;
    } catch (e) { } finally { olderBusy = false; }
  }
  // ChatActivity.ticks: ✗ failed · ✓✓ read (tick_read colour) · ✓✓ delivered · ✓ sent · ⋯ pending
  function ticks(state) {
    if (state === 'failed') return '✗';
    if (state === 'read') return '<span class="read">✓✓</span>';
    if (state === 'delivered') return '✓✓';
    if (state === 'sent') return '✓';
    return '⋯';
  }
  function waveBars(hex) { const out = []; for (let i = 0; i < hex.length; i++) out.push(parseInt(hex[i], 16) / 15); return out; }
  function bubbleHtml(e) {
    const mine = !!e.mine, m = parseMedia(e.body || '');
    let inner = '';
    if (S.openIsGroup && !mine && e.sname) inner += '<div class="sname">' + esc(e.sname) + '</div>';
    if (m) {
      const url = mediaUrl(m);
      if (m.mime.startsWith('image/') && url) inner += '<img class="pic" loading="lazy" alt="photo" src="' + esc(url) + '">';
      else if (m.mime.startsWith('audio/') && url) {
        const parts = m.caption.split('|');
        inner += '<div class="audio" data-src="' + esc(url) + '"><button class="play" title="Play">' + ic('play') + '</button><div class="wave"><canvas data-wave="' + esc(parts[1] || '') + '"></canvas><div class="atime">' + esc(parts[0] || '') + '</div></div></div>';
      } else if (url) inner += '<div class="body"><a href="' + esc(url) + '" download>' + esc(preview(e.body)) + '</a></div>';
      else inner += '<div class="body">' + esc(preview(e.body)) + '</div>';
      if (m.caption && !m.mime.startsWith('audio/')) inner += '<div class="body">' + esc(m.caption) + '</div>';
    } else inner += '<div class="body">' + esc(e.body || '') + '</div>';
    let meta = hhmm(e.time);
    if (mine) meta += ' ' + (S.openIsGroup && e.delivered != null && e.state !== 'read' ? e.delivered + ' ' : '') + ticks(e.state);
    return '<div class="mrow ' + (mine ? 'mine' : 'theirs') + '" data-id="' + esc(e.id) + '"><div class="bubble">' + inner + '<div class="meta">' + meta + '</div></div></div>';
  }
  function renderMsgs(scrollToEnd) {
    const box = $('msgs'); if (!box) return;
    const atEnd = scrollToEnd || (box.scrollHeight - box.scrollTop - box.clientHeight < 80);
    let html = '', lastDay = '';
    for (const e of S.msgs) {
      const d = dayStart(e.time);
      if (d !== lastDay) { html += '<div class="daypill"><span>' + esc(dayLabel(e.time)) + '</span></div>'; lastDay = d; }
      html += bubbleHtml(e);
    }
    box.innerHTML = html;
    box.querySelectorAll('canvas[data-wave]').forEach(drawWave);
    box.querySelectorAll('.audio').forEach(wireAudio);
    box.querySelectorAll('img.pic').forEach((img) => img.addEventListener('click', () => { const v = el('<div class="viewer"><img src="' + esc(img.src) + '"></div>'); v.addEventListener('click', () => v.remove()); document.body.appendChild(v); }));
    if (atEnd) box.scrollTop = box.scrollHeight;
    toBottomBtn();
  }
  function drawWave(c) {
    const bars = waveBars(c.dataset.wave || ''); const n = Math.max(bars.length, 24);
    const dpr = window.devicePixelRatio || 1; const w = c.clientWidth || 160, h = 26;
    c.width = w * dpr; c.height = h * dpr;
    const g = c.getContext('2d'); g.scale(dpr, dpr); g.fillStyle = getComputedStyle(c).color;
    const bw = w / n;
    for (let i = 0; i < n; i++) { const v = bars.length ? bars[i % bars.length] : 0.3; const bh = Math.max(3, v * h); g.fillRect(i * bw + bw * 0.2, (h - bh) / 2, bw * 0.6, bh); }
  }
  function wireAudio(row) {
    let a = null;
    row.querySelector('.play').addEventListener('click', () => {
      if (!a) { a = new Audio(row.dataset.src); a.addEventListener('ended', () => { row.querySelector('.play').innerHTML = ic('play'); }); }
      if (a.paused) { a.play(); row.querySelector('.play').innerHTML = ic('pause'); } else { a.pause(); row.querySelector('.play').innerHTML = ic('play'); }
    });
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
    try { await api('chat.send', { peer, body }); await reloadOpenTail(peer); }
    catch (e) { local.state = 'failed'; renderMsgs(false); showSendErr(e.message); }
  }
  function showSendErr(msg) { const b = $('sendErr'); if (!b) return; b.textContent = msg; b.hidden = false; setTimeout(() => { b.hidden = true; }, 6000); }
  async function reloadOpenTail(peer) {
    const r = await api('chat.conversation', { peer, limit: 30 });
    const tail = r.messages || [];
    S.msgs = S.msgs.filter((m) => !String(m.id).startsWith('local-') || !tail.some((t) => t.mine && (t.body === m.body || (parseMedia(m.body) && parseMedia(t.body) && Number(t.time) >= Number(m.time) - 60000))));
    for (const t of tail) { const i = S.msgs.findIndex((x) => x.id === t.id); if (i >= 0) S.msgs[i] = t; else S.msgs.push(t); }
    S.msgs.sort((a, b) => Number(a.time) - Number(b.time));
    renderMsgs(true);
  }
  async function sendFile(file, asPhoto) {
    const peer = S.open; if (!peer) return;
    let blob = file, mime = file.type || 'application/octet-stream';
    if (asPhoto || mime.startsWith('image/')) {
      try {
        const bmp = await createImageBitmap(file);
        const max = 1600, scale = Math.min(1, max / Math.max(bmp.width, bmp.height));
        const c = document.createElement('canvas'); c.width = Math.round(bmp.width * scale); c.height = Math.round(bmp.height * scale);
        c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
        blob = await new Promise((res) => c.toBlob(res, 'image/jpeg', 0.85)); mime = 'image/jpeg';
      } catch (e) { /* not an image after all: send as is */ }
    }
    const bytes = new Uint8Array(await blob.arrayBuffer());
    if (bytes.length > 16 * 1024 * 1024) { showSendErr('Too big: 16 MB is the most a message can carry'); return; }
    const caption = mime.startsWith('image/') ? '' : (file.name || '');
    const local = { id: 'local-' + Date.now(), body: MEDIA_MARK + mime + '\u0001data:' + mime + ';base64,' + b64(bytes) + '\u0001' + caption, mine: true, time: Date.now(), state: 'sending' };
    S.msgs.push(local); renderMsgs(true);
    const tid = 'p' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8), CH = 48 * 1024;
    try {
      for (let off = 0; off < bytes.length; off += CH) {
        const last = off + CH >= bytes.length;
        const p = { tid, off, data: b64(bytes.subarray(off, Math.min(bytes.length, off + CH))), last };
        if (last) { p.peer = peer; p.group = S.openIsGroup; p.mime = mime; p.caption = caption; }
        await api('media.up', p);
      }
      setTimeout(() => reloadOpenTail(peer).catch(() => {}), 2500);
      setTimeout(() => reloadOpenTail(peer).catch(() => {}), 12000);
    } catch (e) { local.state = 'failed'; renderMsgs(false); showSendErr(e.message); }
  }
  function b64(u8) { let s = ''; for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000)); return btoa(s); }

  // ---------- contact / group sheets ----------
  async function contactInfo(key) {
    let c; try { c = await api('contacts.info', { key }); } catch (e) { toast(e.message, 'err'); return; }
    sheet('<div style="display:flex;align-items:center;gap:12px">' + avatar(key, c.name, 'l') + '<div><div class="h">' + esc(c.name || '(no name)') + '</div><div class="sub">' + esc(presence(c.lastSeen) || 'not reached yet') + '</div></div></div>'
      + '<div class="inner"><div class="sub">Key</div><div class="mono whole">' + esc(c.key || key) + '</div></div>'
      + '<div class="inner"><div class="sub">Address</div><div class="mono whole">' + esc(c.address || '') + '</div></div>'
      + '<div class="frow"><input class="field" id="ciName" value="' + esc(c.name || '') + '" placeholder="Name"><button class="btn sm" id="ciRename">Rename</button></div>'
      + '<button class="btn ghost full" id="ciChat">Open chat</button><button class="btn ghost full" id="ciResolve">Re-resolve their address</button><button class="btn ghost full danger" id="ciRemove">Remove contact</button>', (sh) => {
        sh.querySelector('#ciChat').addEventListener('click', () => { closeSheet(); go('#chat/' + encodeURIComponent(key)); });
        sh.querySelector('#ciRename').addEventListener('click', async () => { try { await api('contacts.rename', { key, name: sh.querySelector('#ciName').value.trim() }); toast('Renamed'); closeSheet(); await loadSummaries(); if (S.route === 'contacts') renderContacts(); if (S.open === key) openChat(key); } catch (e) { toast(e.message, 'err'); } });
        sh.querySelector('#ciResolve').addEventListener('click', async () => { try { const r = await api('contacts.resolve', { key }); toast(r.updated ? 'Address refreshed' : 'No fresher record'); } catch (e) { toast(e.message, 'err'); } });
        sh.querySelector('#ciRemove').addEventListener('click', async () => { if (!confirm('Remove this contact?')) return; try { await api('contacts.remove', { key }); closeSheet(); toast('Removed'); await loadSummaries(); if (S.open === key) $('back').click(); if (S.route === 'contacts') renderContacts(); } catch (e) { toast(e.message, 'err'); } });
      });
  }
  async function groupInfo(id) {
    let g; try { g = await api('group.info', { id }); } catch (e) { toast(e.message, 'err'); return; }
    const rows = (g.members || []).map((m) => '<div class="rowitem">' + avatar(m.key, m.name, 's') + '<div class="mid"><div class="n">' + esc(m.name || '') + (m.me ? ' (you)' : '') + (m.admin ? ' · admin' : '') + '</div><div class="s mono">' + esc(m.key) + '</div></div></div>').join('');
    sheet('<div class="h">' + esc(g.name || '') + '</div><div class="sub">' + (g.members || []).length + ' member(s)</div>'
      + (g.iAmAdmin ? '<div class="frow"><input class="field" id="giName" value="' + esc(g.name || '') + '"><button class="btn sm" id="giRename">Rename</button></div>' : '<div class="sub">Only an admin can change the group.</div>')
      + '<div style="margin-top:8px">' + rows + '</div>', (sh) => {
        const rn = sh.querySelector('#giRename');
        if (rn) rn.addEventListener('click', async () => { try { await api('group.update', { id, name: sh.querySelector('#giName').value.trim() }); toast('Renamed'); closeSheet(); await loadSummaries(); openChat(id); } catch (e) { toast(e.message, 'err'); } });
      });
  }

  // ---------- Contacts page (page_contacts: identity card, search, rows) ----------
  async function renderContacts() {
    const page = $('page'); page.innerHTML = '';
    const body = el('<div class="pagebody"></div>'); page.appendChild(body);
    const idc = el('<div class="card"><div style="display:flex;align-items:center;gap:12px">' + avatar(S.me.permanent, S.me.name, 'l') + '<div style="flex:1;min-width:0"><div class="h">' + esc(S.me.name || 'Your account') + '</div><div class="sub">Your address never changes. Share it as a QR or copy it.</div></div></div>'
      + '<div class="frow"><button class="btn sm" id="myQrBtn">' + ic('qr') + 'Show QR</button><button class="btn ghost sm" id="myCopy">' + ic('copy') + 'Copy address</button></div><div class="qr" id="myQr" hidden></div></div>');
    idc.querySelector('#myCopy').addEventListener('click', () => copy(S.me.permanent));
    idc.querySelector('#myQrBtn').addEventListener('click', () => { const q = idc.querySelector('#myQr'); q.hidden = !q.hidden; if (!q.hidden) drawQr(q, S.me.permanent); });
    body.appendChild(idc);
    const add = el('<div class="card"><div class="h">Add a contact</div><div class="sub">Paste their address (MAX#… or Mx…). They get an introduction; the chat opens once they accept.</div><div class="frow"><input class="field" id="addAddr" placeholder="Paste an address"><button class="btn sm" id="addBtn">' + ic('personadd') + 'Add</button></div></div>');
    add.querySelector('#addBtn').addEventListener('click', async () => { const address = add.querySelector('#addAddr').value.trim(); if (!address) return; try { await api('contacts.add', { address }); toast('Introduction sent'); add.querySelector('#addAddr').value = ''; setTimeout(renderContacts, 1500); } catch (e) { toast(e.message, 'err'); } });
    body.appendChild(add);
    const list = el('<div class="card"><input class="field" id="cSearch" placeholder="Search contacts" style="margin-bottom:6px"><div id="cRows"></div></div>');
    body.appendChild(list);
    try { S.contacts = (await api('contacts.list', { offset: 0, limit: 200 })).contacts || []; } catch (e) { toast(e.message, 'err'); }
    const draw = () => {
      const q = list.querySelector('#cSearch').value.trim().toLowerCase();
      const rows = list.querySelector('#cRows'); rows.innerHTML = '';
      const cs = S.contacts.filter((c) => !q || (c.name || '').toLowerCase().includes(q) || (c.key || '').toLowerCase().includes(q));
      if (!cs.length) rows.innerHTML = '<div class="sub">' + (q ? 'Nothing matches.' : 'No contacts yet.') + '</div>';
      for (const c of cs) {
        const p = presence(c.lastSeen);
        const r = el('<div class="rowitem" style="cursor:pointer">' + avatar(c.key, c.name, 'm') + '<div class="mid"><div class="n">' + esc(c.name || '(no name)') + '</div><div class="s">' + esc(p || 'not reached yet') + '</div></div>' + (p === 'online' ? '<span class="spill ok"><span class="dot"></span>online</span>' : '') + '</div>');
        r.addEventListener('click', () => contactInfo(c.key));
        rows.appendChild(r);
      }
    };
    list.querySelector('#cSearch').addEventListener('input', draw); draw();
  }
  function drawQr(host, text) { if (!text) { host.textContent = ''; return; } try { const q = qrcode(0, 'M'); q.addData(text); q.make(); host.innerHTML = q.createImgTag(3, 0); } catch (e) { host.textContent = ''; } }
  async function copy(text) {
    try { await navigator.clipboard.writeText(text); toast('Copied'); }
    catch (e) { const ta = document.createElement('textarea'); ta.value = text; document.body.appendChild(ta); ta.select(); document.execCommand('copy'); ta.remove(); toast('Copied'); }
  }

  // ---------- Devices page ----------
  async function renderDevices() {
    const page = $('page'); page.innerHTML = '';
    const body = el('<div class="pagebody"></div>'); page.appendChild(body);
    const pair = el('<div class="card"><div class="h">Pair a phone</div><div class="sub" id="invText">No pairing code is outstanding.</div><div class="qr" id="invQr" hidden></div><div class="mono whole" id="invTxt" style="font-size:11px;margin-top:6px"></div><div class="frow"><button class="btn sm" id="newCode">New pairing code</button><button class="btn ghost sm" id="copyInv" hidden>' + ic('copy') + 'Copy invite</button></div></div>');
    body.appendChild(pair);
    const devs = el('<div class="card"><div class="h">Devices</div><div id="devRows"></div></div>'); body.appendChild(devs);
    async function refreshInvite() {
      try {
        const inv = await apiGet('invite');
        const q = pair.querySelector('#invQr');
        if (inv.invite) { pair.querySelector('#invText').textContent = 'Scan this with the Parlons app on your phone, or paste the text. The code half works once.'; q.hidden = false; drawQr(q, inv.invite); pair.querySelector('#invTxt').textContent = inv.invite; pair.querySelector('#copyInv').hidden = false; pair.querySelector('#copyInv').onclick = () => copy(inv.invite); }
        else { pair.querySelector('#invText').textContent = 'No pairing code is outstanding.'; q.hidden = true; pair.querySelector('#invTxt').textContent = ''; pair.querySelector('#copyInv').hidden = true; }
      } catch (e) { }
    }
    async function refreshDevices() {
      try {
        const d = await api('pair.list'); const rows = devs.querySelector('#devRows'); rows.innerHTML = '';
        for (const dev of (d.authorized || [])) {
          const r = el('<div class="rowitem"><div class="mid"><div class="n">' + esc(dev.label) + (dev.local ? ' <span class="spill">this computer</span>' : '') + '</div><div class="s mono">' + esc(dev.key) + '</div></div><button class="btn ghost sm danger">Revoke</button></div>');
          r.querySelector('button').addEventListener('click', async () => { if (!confirm('Revoke this device? It loses access to the account.')) return; try { await api('pair.revoke', { device: dev.key }); toast('Revoked'); refreshDevices(); } catch (e) { toast(e.message, 'err'); } });
          rows.appendChild(r);
        }
        for (const k of (d.pending || [])) {
          const r = el('<div class="rowitem"><div class="mid"><div class="n">Waiting for approval</div><div class="s mono">' + esc(k) + '</div></div><button class="btn sm">Approve</button></div>');
          r.querySelector('button').addEventListener('click', async () => { try { await api('pair.approve', { device: k }); toast('Approved'); refreshDevices(); } catch (e) { toast(e.message, 'err'); } });
          rows.appendChild(r);
        }
        if (!rows.children.length) rows.innerHTML = '<div class="sub">none</div>';
      } catch (e) { toast(e.message, 'err'); }
    }
    pair.querySelector('#newCode').addEventListener('click', async () => { try { await api('pair.newcode'); toast('New code minted'); setTimeout(refreshInvite, 900); } catch (e) { toast(e.message, 'err'); } });
    refreshInvite(); refreshDevices();
  }

  // ---------- Node page ----------
  let nodeTimer = null;
  async function renderNode() {
    // Live figures: the relay learns its address and proves its reach minutes after start, so the
    // page re-reads itself every 15 s while it is the one showing (never while a field is focused).
    clearInterval(nodeTimer);
    nodeTimer = setInterval(() => { if (S.route === 'node' && !document.querySelector('#page input:focus')) renderNodeBody(); else if (S.route !== 'node') clearInterval(nodeTimer); }, 15000);
    return renderNodeBody();
  }
  async function renderNodeBody() {
    const page = $('page'); page.innerHTML = '';
    const body = el('<div class="pagebody"></div>'); page.appendChild(body);
    let st = {}, fig = {};
    try { st = await api('node.status'); } catch (e) { toast(e.message, 'err'); }
    try { fig = await api('node.figures'); } catch (e) { }
    const up = Number(st.uptime || 0), h = Math.floor(up / 3600000), m = Math.floor((up % 3600000) / 60000);
    const anchor = (st.permanent || '').split('@').pop();
    const ownAnchor = !!fig.ownRelay && anchor === fig.ownRelay;
    body.appendChild(el('<div class="card"><div class="ctitle2">Account</div>'
      + '<div class="metric"><span class="k">Name</span><span class="v">' + esc(st.name) + '</span></div>'
      + '<div class="metric"><span class="k">Version</span><span class="v">' + esc(st.version) + '</span></div>'
      + '<div class="metric"><span class="k">Up</span><span class="v">' + h + ' h ' + m + ' min</span></div>'
      + '<div class="metric"><span class="k">Relays attached</span><span class="v">' + esc(st.hosts) + '</span></div>'
      + '<div class="metric"><span class="k">Mesh peers</span><span class="v">' + esc(st.meshPeers) + '</span></div>'
      + '<div class="metric"><span class="k">Paired devices</span><span class="v">' + esc(st.pairedDevices) + '</span></div>'
      + '<div class="metric"><span class="k">Mailbox / outbox</span><span class="v">' + esc(fig.mailboxHeld == null ? '' : fig.mailboxHeld) + ' / ' + esc(fig.outbox == null ? '' : fig.outbox) + '</span></div>'
      + '<div class="inner"><div class="sub">Permanent address' + (ownAnchor ? ' · anchored on your own relay' : ' · anchored on relay ' + esc(anchor) + ', a fleet directory that resolves this address for your contacts (not your machine)') + '</div><div class="mono whole" style="font-size:11px;margin-top:4px">' + esc(st.permanent) + '</div></div>'
      + (fig.directAddress ? '<div class="inner"><div class="sub">Direct address</div><div class="mono whole" style="font-size:11px">' + esc(fig.directAddress) + '</div></div>' : '') + '</div>'));
    // Your own relay (the cape): what it is, whether the world can reach it, what it carries.
    const rs = fig.ownRelayState || (st.relayOn ? 'nohost' : 'off');
    const relayLine = rs === 'off' ? 'Off. Turn on Contribute in the node app to run a relay for the network.'
      : rs === 'nohost' ? 'Running, but this machine does not know its public address yet - it learns it from its peers a few minutes after start; the account adopts the relay then.'
      : rs === 'verified' ? 'Reachable from the internet ✓ - your contacts reach you through it and it anchors your permanent address.'
      : rs === 'unreachable' ? 'Running, but no connection from the internet has reached port ' + esc(String(fig.ownRelay || '').split(':').pop()) + ' yet: the router must forward that TCP port to this machine. Until then the fleet anchors your address (checked again every 10 minutes).'
      : rs === 'attached' ? 'Attached; waiting for the first connection from the internet to prove the port is open…' : 'Attaching…';
    body.appendChild(el('<div class="card"><div class="ctitle2">Your relay</div>'
      + '<div class="metric"><span class="k">Address</span><span class="v mono">' + esc(fig.ownRelay || (st.relayOn ? '(public address not known yet)' : '—')) + '</span></div>'
      + '<div class="metric"><span class="k">State</span><span class="v"><span class="spill' + (rs === 'verified' ? ' ok' : rs === 'unreachable' ? ' bad' : '') + '"><span class="dot"></span>' + esc(rs === 'nohost' ? 'no public address yet' : rs) + '</span></span></div>'
      + '<div class="metric"><span class="k">Connections</span><span class="v">' + esc(fig.relayConnections == null ? '—' : fig.relayConnections) + '</span></div>'
      + '<div class="metric"><span class="k">Relayed / stored</span><span class="v">' + esc(fig.relayRelayed == null ? '—' : fig.relayRelayed) + ' / ' + esc(fig.relayStored == null ? '—' : fig.relayStored) + '</span></div>'
      + '<div class="sub" style="margin-top:8px">' + relayLine + '</div></div>'));
    const hosts = el('<div class="card"><div class="ctitle2">Relays</div><div id="hostRows"></div><div class="frow"><input class="field" id="hostAdd" placeholder="host:port, or a relay QR text"><button class="btn sm" id="hostAddBtn">Add</button></div><div class="sw"><div class="lbl">Use the built-in relay list<small>One seed source among several; switch it off once you have relays of your own.</small></div><button class="switch' + (fig.builtin ? ' on' : '') + '" id="builtin"></button></div></div>');
    const hr = hosts.querySelector('#hostRows');
    for (const hh of (fig.hosts || [])) {
      const r = el('<div class="rowitem"><span class="spill' + (hh.connected ? ' ok' : '') + '"><span class="dot"></span>' + (hh.connected ? 'connected' : 'not attached') + '</span><div class="mid"><div class="s mono">' + esc(hh.host) + '</div></div>' + (hh.builtin ? '<span class="spill">built-in</span>' : '<button class="btn ghost sm danger">Remove</button>') + '</div>');
      const rb = r.querySelector('button'); if (rb) rb.addEventListener('click', async () => { try { await api('node.hosts', { remove: hh.host }); toast('Detached'); renderNode(); } catch (e) { toast(e.message, 'err'); } });
      hr.appendChild(r);
    }
    hosts.querySelector('#hostAddBtn').addEventListener('click', async () => { const v = hosts.querySelector('#hostAdd').value.trim(); if (!v) return; try { await api('node.hosts', { add: v }); toast('Connecting…'); setTimeout(renderNode, 1500); } catch (e) { toast(e.message, 'err'); } });
    hosts.querySelector('#builtin').addEventListener('click', async (ev) => { const on = !ev.currentTarget.classList.contains('on'); try { await api('node.hosts', { builtin: on }); ev.currentTarget.classList.toggle('on', on); toast(on ? 'Built-in list on' : 'Built-in list off'); } catch (e) { toast(e.message, 'err'); } });
    body.appendChild(hosts);
    const mls = el('<div class="card"><div class="ctitle2">Directory anchor</div><div class="sub">Where your permanent address resolves. Pin it to one relay you trust, or let the account choose.</div><div class="frow"><button class="btn ghost sm" id="mlsPin">Pin best relay</button><button class="btn ghost sm" id="mlsClear">Clear</button><button class="btn ghost sm" id="mlsRepub">Republish</button></div><div class="mono whole" id="mlsInfo" style="font-size:11px;margin-top:6px"></div></div>');
    async function mlsDo(action) { try { const r = await api('node.mls', { action }); mls.querySelector('#mlsInfo').textContent = (r.pinned ? 'pinned: ' : 'auto: ') + (r.mls || ''); toast('Done'); } catch (e) { toast(e.message, 'err'); } }
    mls.querySelector('#mlsPin').addEventListener('click', () => mlsDo('pin')); mls.querySelector('#mlsClear').addEventListener('click', () => mlsDo('clear')); mls.querySelector('#mlsRepub').addEventListener('click', () => mlsDo('republish'));
    body.appendChild(mls);
    const log = el('<div class="card"><div class="ctitle2">Log</div><div class="log" id="nodeLog">…</div><div class="frow"><button class="btn ghost sm" id="logRefresh">Refresh</button><button class="btn ghost sm" id="logClear">Clear</button></div></div>');
    async function loadLog(clear) { try { const r = await api('node.log', clear ? { clear: true } : {}); log.querySelector('#nodeLog').textContent = (r.lines || []).join('\n') || '(empty)'; } catch (e) { toast(e.message, 'err'); } }
    log.querySelector('#logRefresh').addEventListener('click', () => loadLog(false)); log.querySelector('#logClear').addEventListener('click', () => loadLog(true));
    body.appendChild(log); loadLog(false);
  }

  // ---------- Settings page ----------
  async function renderSettings() {
    const page = $('page'); page.innerHTML = '';
    const body = el('<div class="pagebody"></div>'); page.appendChild(body);
    let s = {}; try { s = await api('settings.get'); } catch (e) { }
    const name = el('<div class="card"><div class="ctitle2">Profile</div><div class="h">Your name</div><div class="sub">What your contacts see.</div><div class="frow"><input class="field" id="setName" value="' + esc(S.me.name) + '"><button class="btn sm" id="saveName">Save</button></div></div>');
    name.querySelector('#saveName').addEventListener('click', async () => { const v = name.querySelector('#setName').value.trim(); if (!v) return; try { await api('identity.setname', { name: v }); S.me.name = v; toast('Saved'); } catch (e) { toast(e.message, 'err'); } });
    body.appendChild(name);
    const priv = el('<div class="card"><div class="ctitle2">Privacy</div><div class="sw"><div class="lbl">Read receipts<small>Your contacts see when you have read their messages.</small></div><button class="switch' + (s.readReceipts ? ' on' : '') + '" id="rr"></button></div></div>');
    priv.querySelector('#rr').addEventListener('click', async (ev) => { const on = !ev.currentTarget.classList.contains('on'); try { await api('settings.set', { readReceipts: on }); ev.currentTarget.classList.toggle('on', on); toast('Saved'); } catch (e) { toast(e.message, 'err'); } });
    body.appendChild(priv);
    const th = el('<div class="card"><div class="ctitle2">Appearance</div><div class="sub">Theme follows the button in the top bar: system, light, dark.</div></div>');
    body.appendChild(th);
    const ab = el('<div class="card"><div class="ctitle2">This panel</div><div class="sub">Signed in as this computer, a local device of the account. The seed, the wallet and the node console stay on your paired phones and the command line.</div><div class="frow"><button class="btn ghost sm" id="openBrowser">Open in a browser</button><button class="btn ghost sm" id="signOut">Sign out</button></div><div class="sub" style="margin-top:10px">Parlons ' + esc(S.version) + ' · Powered by Maxima</div></div>');
    ab.querySelector('#openBrowser').addEventListener('click', async () => { try { const r = await api('ticket'); window.open(r.url, '_blank'); } catch (e) { toast(e.message, 'err'); } });
    ab.querySelector('#signOut').addEventListener('click', async () => { try { await api('logout'); } catch (e) {} location.reload(); });
    body.appendChild(ab);
  }

  async function refreshPill() {
    try { const st = await api('node.status'); S.version = st.version || ''; $('ver').textContent = S.version ? 'v' + S.version : ''; const n = Number(st.hosts || 0); setState(n + (n === 1 ? ' host' : ' hosts'), n > 0 ? 'ok' : 'bad'); }
    catch (e) { setState('connected', 'ok'); }
  }

  // ---------- live events ----------
  let es = null;
  function listen() {
    if (es) { try { es.close(); } catch (e) {} }
    es = new EventSource('/events');
    es.addEventListener('hello', () => { refreshPill(); catchUp(); });
    es.addEventListener('push', (ev) => { let e; try { e = JSON.parse(ev.data); } catch (x) { return; } handleEvent(e); });
    es.onerror = () => { setState('reconnecting…', 'bad'); };
  }
  async function catchUp() {
    if (!S.lastEvent) { await loadSummaries().catch(() => {}); return; }
    try { const r = await api('chat.since', { cursor: S.lastEvent, limit: 100 }); for (const e of (r.entries || [])) if (S.open && e.peer === S.open) upsertMsg(e); } catch (e) { }
    await loadSummaries().catch(() => {});
  }
  function handleEvent(e) {
    if (e.time) S.lastEvent = Math.max(S.lastEvent, Number(e.time));
    if (e.type === 'message') {
      const mine = !!e.mine;   // sent from another device of this account (or this panel itself)
      if (S.open === e.peer) {
        if (mine) S.msgs = S.msgs.filter((m) => !String(m.id).startsWith('local-') || m.body !== e.body);   // our optimistic echo, if this panel sent it
        upsertMsg({ id: e.id, body: e.body, mine, sender: e.sender, sname: e.sname, time: e.time, state: mine ? (e.state || '') : '' });
        if (!mine) api('chat.markread', { peer: e.peer }).catch(() => {});
      }
      loadSummaries().catch(() => {});
      if (!mine && (document.hidden || S.open !== e.peer)) notify(e);
    } else if (e.type === 'state') {
      if (S.open === e.peer) { const m = S.msgs.find((x) => x.id === e.id); if (m) { m.state = e.state; renderMsgs(false); } }
    } else if (e.type === 'call') {
      if (e.kind === 'offer') showBanner((e.name || 'Someone') + ' is calling - answer on your phone', 30000);
      else if (e.kind === 'taken' || e.kind === 'bye') hideBanner();
    }
  }
  let bannerTimer = null;
  function showBanner(text, ms) { const b = $('banner'); b.textContent = text; b.hidden = false; clearTimeout(bannerTimer); if (ms) bannerTimer = setTimeout(hideBanner, ms); }
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
      await refreshPill();
    } catch (e) { setState('no account', 'bad'); return; }
    await loadSummaries().catch((e) => toast(e.message, 'err'));
    if ('Notification' in window && Notification.permission === 'default') { try { Notification.requestPermission(); } catch (e) {} }
    listen();
    render();
  }
  boot();
})();
