// Parlons panel - the Parlons phone app, rendered as it is, over the account's local API.
// Left: the phone (home app bar, tab strip, pages). Right (wide windows): the chat screen. Every call is
// POST /api/<method> with the session cookie; live events on /events (SSE). No framework, no inline script.
(function () {
  'use strict';
  const I = window.ParlonsI18n, tr = I.t;

  // ---------- helpers ----------
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  // Explicit UI labels only. Parameters are escaped for both the attribute and text contexts.
  const label = (key, vars = {}) => '<span data-i18n="' + esc(key) + '" data-i18n-vars="' + esc(JSON.stringify(vars)) + '">' + esc(tr(key, vars)) + '</span>';
  function linkText(text) {
    text = String(text || '');
    const re = /(?<![\p{L}\p{N}_@./:+%\-])(?:[a-z][a-z0-9+.-]*:\/\/[^\s<>"\x00-\x1f\x7f]+|(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.){1,126}[a-z]{2,63}(?::[0-9]{1,5})?(?:[/?#][^\s<>"\x00-\x1f\x7f]*)?)(?![a-z0-9_\-])/giu;
    let html = '', offset = 0, match;
    while ((match = re.exec(text))) {
      const token = match[0], balance = [0, 0, 0];
      for (const c of token) {
        const open = '([{'.indexOf(c), close = ')]}'.indexOf(c);
        if (open >= 0) balance[open]++;
        if (close >= 0) balance[close]--;
      }
      let end = token.length;
      while (end > 0) {
        const last = token[end - 1], close = ')]}'.indexOf(last);
        if (".,!?;:'".includes(last)) { end--; continue; }
        if (close >= 0 && balance[close] < 0) { balance[close]++; end--; continue; }
        break;
      }
      const raw = token.slice(0, end);
      const href = raw.includes('://') ? raw : 'https://' + raw;
      try {
        const url = new URL(href);
        if (!['http:', 'https:'].includes(url.protocol) || !url.hostname || url.hostname.length > 253 || url.username || url.password) continue;
        html += esc(text.slice(offset, match.index)) + '<a class="chat-link" href="' + esc(href) + '" target="_blank" rel="noopener noreferrer">' + esc(raw) + '</a>';
        offset = match.index + raw.length;
      } catch (_) {}
    }
    return html + esc(text.slice(offset));
  }
  const el = (html) => { const t = document.createElement('template'); t.innerHTML = html.trim(); const node = t.content.firstElementChild; I.apply(node); return node; };
  const MEDIA_MARK = '\u0001m\u0001';   // core ChatMedia: SOH-fenced 'm', then mime SOH ref SOH caption
  const CONTACT_MARK = '\u0001c\u0001'; // core ChatContact: SOH-fenced 'c', then key SOH name SOH address
  function parseContact(body) {
    if (!body || !body.startsWith(CONTACT_MARK)) return null;
    const rest = body.slice(CONTACT_MARK.length);
    const a = rest.indexOf('\u0001'); if (a < 0) return null;
    const rest2 = rest.slice(a + 1);
    const b = rest2.indexOf('\u0001'); if (b < 0) return null;
    return { key: rest.slice(0, a), name: rest2.slice(0, b), address: rest2.slice(b + 1) };
  }
  function wrapContact(key, name, address) { const clean = (x) => String(x || '').split('\u0001').join(''); return CONTACT_MARK + clean(key) + '\u0001' + clean(name) + '\u0001' + clean(address); }
  const ic = window.icon;

  let signedOut = false;
  async function api(method, body) {
    const r = await fetch('/api/' + method, { method: 'POST', credentials: 'same-origin', signal: method === 'call.signal' ? AbortSignal.timeout(10000) : undefined, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
    if (r.status === 401) { signedOut = true; setState('connection.signedOut', 'bad'); throw new Error(tr('connection.freshLink')); }
    const j = await r.json().catch(() => ({ ok: false, error: tr('connection.badReply') }));
    if (!r.ok || (j && j.ok === false)) throw new Error((j && j.error) || tr('connection.operationFailed', { operation: method }));
    return j;
  }
  async function apiGet(name) {
    const r = await fetch('/api/' + name, { credentials: 'same-origin' });
    if (r.status === 401) { signedOut = true; setState('connection.signedOut', 'bad'); throw new Error(tr('connection.signedOut')); }
    if (!r.ok) throw new Error(tr('connection.loadFailed', { resource: name }));
    return r.json();
  }
  let toastTimer = null;
  function toast(msg, kind) {
    const t = $('toast');
    if (kind === 'err') I.render(t, () => I.system(msg)); else I.text(t, msg);
    t.className = 'toast' + (kind === 'err' ? ' err' : ''); t.hidden = false;
    clearTimeout(toastTimer); toastTimer = setTimeout(() => { t.hidden = true; }, kind === 'err' ? 5000 : 2400);
  }
  function setState(key, kind, vars = {}) {
    if (signedOut) { key = 'connection.signedOut'; kind = 'bad'; }
    I.bind($('state'), key, vars);
    const p = $('state').parentElement; p.className = 'hpill' + (kind === 'ok' ? ' ok' : kind === 'bad' ? ' bad' : '');
  }
  const pad2 = (n) => (n < 10 ? '0' : '') + n;
  function hhmm(ms) { return I.date(ms, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }); }
  function dayStart(ms) { const d = new Date(Number(ms)); d.setHours(0, 0, 0, 0); return d.getTime(); }
  // ChatActivity.dayLabel: Today / Yesterday / weekday (< 7 days) / "7 Sep 2026"
  function dayLabel(ms) {
    const days = Math.round((dayStart(Date.now()) - dayStart(ms)) / 86400000);
    if (days === 0) return tr('date.today');
    if (days === 1) return tr('date.yesterday');
    if (days > 1 && days < 7) return I.date(ms, { weekday: 'long' });
    return I.date(ms, { day: 'numeric', month: 'short', year: 'numeric' });
  }
  // the list's time column: HH:mm today, else "30 Aug"
  function listTime(ms) {
    if (!ms) return '';
    return dayStart(ms) === dayStart(Date.now()) ? hhmm(ms) : I.date(ms, { day: 'numeric', month: 'short' });
  }
  // Presence.of: online < 30 min, then last seen Nm / Nh / Nd ago
  function isOnline(lastSeen) { return !!lastSeen && Date.now() - Number(lastSeen) < 30 * 60000; }
  function presence(lastSeen) {
    if (!lastSeen) return '';
    const d = Date.now() - Number(lastSeen);
    if (d < 30 * 60000) return tr('presence.online');
    const m = Math.floor(d / 60000); if (m < 60) return tr('presence.lastSeen', { time: I.relative(-m, 'minute') });
    const h = Math.floor(m / 60); if (h < 24) return tr('presence.lastSeen', { time: I.relative(-h, 'hour') });
    return tr('presence.lastSeen', { time: I.relative(-Math.floor(h / 24), 'day') });
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
  function typedPreview(kind, text) {
    if (typeof kind !== 'string' || typeof text !== 'string') return null;
    if (kind === 'payment') return text;
    if (kind === 'file') return '📎 ' + text;
    if (kind === 'contact') return text ? tr('media.contact', { name: text }) : tr('media.unnamedContact');
    const key = { photo: 'media.photo', video: 'media.video', voice: 'media.voice' }[kind];
    return typeof key === 'string' ? (text ? tr(key + 'Caption', { caption: text }) : tr(key)) : null;
  }
  function preview(body) {
    const c = parseContact(body);
    if (c) return typedPreview('contact', c.name);
    const m = parseMedia(body);
    if (!m) return body;
    if (m.ref.startsWith('pf1:')) return typedPreview('file', m.caption);
    const kind = m.mime.startsWith('video') ? 'video' : m.mime.startsWith('audio') ? 'voice' : 'photo';
    let cap = m.caption;
    if (kind === 'voice' && cap.indexOf('|') >= 0) cap = cap.slice(0, cap.indexOf('|'));
    return typedPreview(kind, cap);
  }
  function summaryPreview(summary) {
    const metadata = summary.lastPreview;
    const translated = metadata && typedPreview(metadata.kind, metadata.text);
    return typeof translated === 'string' ? translated : preview(summary.last || '');
  }

  // ---------- state ----------
  const S = { me: { name: '', permanent: '', primary: '' }, version: '', summaries: [], contacts: [], open: null, openIsGroup: false, openName: '', msgs: [], route: 'chats', lastEvent: 0, search: '', showSearch: false, summariesLoaded: false, summariesError: '' };

  // ---------- theme (the app's theme button: system → light → dark) ----------
  function applyTheme() {
    const t = localStorage.getItem('parlons.theme') || 'system';
    if (t === 'system') document.documentElement.removeAttribute('data-theme'); else document.documentElement.setAttribute('data-theme', t);
  }
  $('btnTheme').innerHTML = ic('theme');
  $('btnTheme').addEventListener('click', () => {
    const t = localStorage.getItem('parlons.theme') || 'system';
    const next = t === 'system' ? 'light' : t === 'light' ? 'dark' : 'system';
    localStorage.setItem('parlons.theme', next); applyTheme(); toast(next === 'system' ? tr('theme.system') : next === 'light' ? tr('theme.light') : tr('theme.dark'));
  });
  applyTheme();
  $('btnSearch').innerHTML = ic('search');
  $('btnSearch').addEventListener('click', () => { S.showSearch = !S.showSearch; if (S.route !== 'chats') go('#chats'); else renderChats(); setTimeout(() => { const f = $('search'); if (f) f.focus(); }, 50); });
  $('btnMore').innerHTML = ic('more');
  $('btnMore').addEventListener('click', () => sheet('<div class="h">Parlons</div><div class="sub"><span data-i18n="common.localDevice">Signed in as this computer, a local device of the account.</span></div>'
    + '<button class="btn ghost full" id="shLanguage">' + ic('language') + 'English · 简体中文 · Русский</button><button class="btn ghost full" id="shBrowser"><span data-i18n="common.openBrowser">Open in a browser</span></button><button class="btn ghost full" id="shOut"><span data-i18n="common.signOut">Sign out</span></button>', (sh) => {
      sh.querySelector('#shLanguage').addEventListener('click', () => { closeSheet(); I.chooser(); });
      sh.querySelector('#shBrowser').addEventListener('click', async () => { try { const r = await api('ticket'); window.open(r.url, '_blank'); } catch (e) { toast(e.message, 'err'); } closeSheet(); });
      sh.querySelector('#shOut').addEventListener('click', async () => { try { await api('logout'); } catch (e) {} location.reload(); });
    }));

  document.addEventListener('keydown', (e) => {
    if ((e.key === 'Enter' || e.key === ' ') && e.target.matches('[role="button"]')) { e.preventDefault(); e.target.click(); }
  });

  function retryCard(host, title, message, retry) {
    const card = el('<div class="card" role="status"><div class="h">' + label(title) + '</div><div class="sub">' + esc(message) + '</div><button class="btn ghost full"><span data-i18n="common.retry">Try again</span></button></div>');
    I.render(card.querySelector('.sub'), () => I.system(message));
    card.querySelector('button').addEventListener('click', retry); host.appendChild(card);
  }
  function wireSwitch(button, label, save, message) {
    button.setAttribute('role', 'switch'); I.attribute(button, 'aria-label', label);
    button.setAttribute('aria-checked', String(button.classList.contains('on')));
    button.addEventListener('click', async () => {
      if (button.disabled) return;
      const on = !button.classList.contains('on'); button.disabled = true;
      try { await save(on); button.classList.toggle('on', on); button.setAttribute('aria-checked', String(on)); toast(message(on)); }
      catch (e) { toast(e.message, 'err'); }
      finally { button.disabled = false; }
    });
  }

  // ---------- bottom sheets (the app's sheet vocabulary) ----------
  let sheetFocus = null;
  function sheet(html, wire) {
    closeSheet(); sheetFocus = document.activeElement;
    const back = el('<div class="sheetback" id="sheet"><div class="sheet" role="dialog" aria-modal="true" aria-labelledby="sheetTitle"><div class="grip"></div>' + html + '<button class="btn ghost full" id="sheetClose" data-i18n="language.close"><span data-i18n="language.close">Close</span></button></div></div>');
    back.querySelector('.h').id = 'sheetTitle';
    back.querySelector('#sheetClose').addEventListener('click', closeSheet);
    back.addEventListener('click', (e) => { if (e.target === back) closeSheet(); });
    back.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') { e.preventDefault(); closeSheet(); return; }
      if (e.key !== 'Tab') return;
      const fields = [...back.querySelectorAll('button, input, textarea, a[href], [tabindex="0"]')].filter((x) => !x.disabled && !x.hidden);
      const first = fields[0], last = fields[fields.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    });
    document.body.appendChild(back); $('app').inert = true;
    if (wire) wire(back);
    (back.querySelector('input:not([type="checkbox"]), textarea') || back.querySelector('button')).focus();
    return back;
  }
  function closeSheet() {
    const s = $('sheet'); if (!s) return;
    s.remove(); $('app').inert = false;
    if (sheetFocus && sheetFocus.isConnected) sheetFocus.focus();
    sheetFocus = null;
  }

  const privateFiles = window.ParlonsFiles.create({ api, sheet, closeSheet, esc, toast });

  // ---------- routing ----------
  function go(hash) { location.hash = hash; }
  window.addEventListener('hashchange', render);
  document.querySelectorAll('.tabs button').forEach((b) => b.addEventListener('click', () => go('#' + b.dataset.route)));
  function render() {
    const h = location.hash.replace(/^#/, '') || 'chats';
    const [route, arg] = h.split('/');
    if (route === 'chat' && arg) { openChat(decodeURIComponent(arg)); if (S.route !== 'chats') { S.route = 'chats'; renderChats(); } setTab('chats'); return; }
    closeSheet();
    S.open = null; S.msgs = []; ++openSeq; olderBusy = false; seekingMessage = false; searchTarget = null;
    $('chatpane').innerHTML = '<div class="ground"><div class="groundText" data-i18n="chat.pick"><span data-i18n="chat.pick">Pick a conversation.</span></div></div>'; I.apply($('chatpane'));
    S.route = route; setTab(route);
    $('app').classList.remove('chat');
    if (route === 'contacts') renderContacts();
    else if (route === 'devices') renderDevices();
    else if (route === 'node') renderNode();
    else if (route === 'settings') renderSettings();
    else renderChats();
  }
  function setTab(route) { document.querySelectorAll('.tabs button').forEach((b) => { const on = b.dataset.route === route; b.classList.toggle('on', on); if (on) b.setAttribute('aria-current', 'page'); else b.removeAttribute('aria-current'); }); }

  // ---------- Chats page ----------
  async function loadSummaries() {
    try {
      let offset = 0;
      const rows = [];
      for (let page = 0; ; page++) {
        const r = await api('chat.summaries', { offset, limit: 100 });
        rows.push(...(r.summaries || []));
        if (!r.more) break;
        const next = Number(r.next);
        if (!Number.isSafeInteger(next) || next <= offset || page >= 99) throw new Error(tr('chat.refreshFailed'));
        offset = next;
      }
      S.summaries = rows.sort((a, b) => Number(b.time || 0) - Number(a.time || 0));
      S.summariesError = '';
    } catch (e) { S.summariesError = e.message; throw e; }
    finally { S.summariesLoaded = true; if (S.route === 'chats') { if ($('convs')) renderChatsList(); else renderChats(); } }
  }
  function renderChats() {
    const page = $('page'); page.innerHTML = '';
    if (S.showSearch || S.search) {
      const f = el('<input class="search" id="search" type="search" placeholder="Search chats" data-i18n-placeholder="chat.search" autocomplete="off" value="' + esc(S.search) + '">');
      let t = null; f.addEventListener('input', () => { S.search = f.value; clearTimeout(t); t = setTimeout(renderChatsList, 200); });
      page.appendChild(f);
    }
    page.appendChild(el('<div class="convs" id="convs"></div>'));
    const fab = el('<button class="fab" id="fab" title="New group" data-i18n-title="chat.newGroup">' + ic('newchat') + '</button>');
    fab.addEventListener('click', newGroup);
    page.appendChild(fab);
    renderChatsList();
  }
  function renderChatsList() {
    const box = $('convs'); if (!box) return;
    box.innerHTML = '';
    const q = S.search.trim().toLowerCase();
    let rows = S.summaries;
    if (q) rows = rows.filter((s) => (s.name || '').toLowerCase().includes(q) || summaryPreview(s).toLowerCase().includes(q));
    if (S.summariesError) retryCard(box, 'chat.refreshFailed', S.summariesError, () => loadSummaries().catch(() => {}));
    if (!rows.length && !S.summariesError) box.appendChild(el('<div class="emptyPage" role="status">' + label(!S.summariesLoaded ? 'chat.loadingChats' : q ? 'chat.noMatches' : 'chat.empty') + '</div>'));
    for (const s of rows) {
      const unread = s.peer === S.open ? 0 : Number(s.unread || 0);
      const last = () => s.last ? (s.lastMine ? tr('chat.youPrefix', { message: summaryPreview(s) }) : (s.group && s.lastName ? s.lastName + ': ' : '') + summaryPreview(s)) : tr('chat.noMessages');
      const row = el('<div role="button" tabindex="0" class="conv' + (S.open === s.peer ? ' on' : '') + '">' + avatar(s.peer, s.name, 'l')
        + '<div class="mid"><div class="name">' + esc(s.name || s.peer) + '</div><div class="prev">' + esc(last()) + '</div></div>'
        + '<div class="right"><div class="time">' + esc(listTime(s.time)) + '</div>' + (unread ? '<div class="badge">' + unread + '</div>' : '') + '</div></div>');
      I.render(row.querySelector('.prev'), last); I.render(row.querySelector('.time'), () => listTime(s.time));
      row.addEventListener('click', () => go('#chat/' + encodeURIComponent(s.peer)));
      box.appendChild(row);
    }
    const seq = ++searchSeq;
    if (q) searchMessages(q, box, seq);
  }
  let searchSeq = 0;
  async function searchMessages(q, box, seq) {
    try {
      const r = await api('chat.search', { q });
      if (seq !== searchSeq || !box.isConnected || S.search.trim().toLowerCase() !== q) return;
      for (const m of (r.messages || [])) {
        const row = el('<div role="button" tabindex="0" class="conv">' + avatar(m.peer, m.name, 'l') + '<div class="mid"><div class="name">' + esc(m.name) + '</div><div class="prev">' + esc((m.mine ? tr('chat.youPrefix', { message: m.body }) : m.body)) + '</div></div><div class="right"><div class="time">' + esc(listTime(m.time)) + '</div></div></div>');
        const visit = () => {
          searchTarget = { peer: m.peer, id: m.id, time: Number(m.time) };
          const hash = '#chat/' + encodeURIComponent(m.peer);
          if (location.hash === hash) openChat(m.peer); else go(hash);
        };
        I.render(row.querySelector('.prev'), () => m.mine ? tr('chat.youPrefix', { message: m.body }) : m.body);
        I.render(row.querySelector('.time'), () => listTime(m.time));
        row.addEventListener('click', visit);
        box.appendChild(row);
      }
    } catch (e) { /* best effort */ }
  }
  async function newGroup() {
    let contacts = [];
    try { contacts = (await api('contacts.list', { offset: 0, limit: 200 })).contacts || []; } catch (e) { toast(e.message, 'err'); return; }
    const picks = contacts.map((c) => '<label><input type="checkbox" value="' + esc(c.key) + '"> ' + esc(c.name || c.key) + '</label>').join('') || '<div class="sub"><span data-i18n="contacts.empty">No contacts yet.</span></div>';
    sheet('<div class="h"><span data-i18n="chat.newGroup">New group</span></div><div class="sub"><span data-i18n="chat.groupLimit">Up to 12 members including you.</span></div><div class="frow"><input class="field" id="gName" placeholder="Group name" data-i18n-placeholder="chat.groupName"></div><div class="pick">' + picks + '</div><button class="btn full" id="gCreate"><span data-i18n="chat.createGroup">Create group</span></button>', (sh) => {
      sh.querySelector('#gCreate').addEventListener('click', async () => {
        const name = sh.querySelector('#gName').value.trim(), members = [...sh.querySelectorAll('input:checked')].map((i) => i.value);
        if (!name || !members.length) { toast(tr('chat.groupRequired'), 'err'); return; }
        try { await api('group.create', { name, members }); closeSheet(); toast(tr('chat.groupCreated')); await loadSummaries(); } catch (e) { toast(e.message, 'err'); }
      });
    });
  }

  // ---------- the chat screen ----------
  let openSeq = 0, olderBusy = false, olderDone = false, searchTarget = null, seekingMessage = false;
  async function openChat(peer) {
    const seq = ++openSeq;
    closeSheet();
    const target = searchTarget && searchTarget.peer === peer ? searchTarget : null;
    searchTarget = null; seekingMessage = !!target;
    S.open = peer; S.msgs = []; olderDone = false; olderBusy = false;
    const sum = S.summaries.find((s) => s.peer === peer);
    S.openIsGroup = !!(sum && sum.group);
    S.openName = (sum && sum.name) || peer;
    renderChatsList();
    $('app').classList.add('chat');
    const pane = $('chatpane'); pane.innerHTML = '';
    pane.appendChild(el('<div class="cbar"><button class="ibtn back" id="back" aria-label="Back to chats" data-i18n-aria-label="chat.back">' + ic('back') + '</button>' + avatar(peer, S.openName, 'm')
      + '<div class="titles"><div class="ctitle">' + esc(S.openName) + '</div><div class="csub" id="csub"></div></div>'
      + '<button class="ibtn" id="cTheme" aria-label="Theme" data-i18n-aria-label="common.theme">' + ic('theme') + '</button><button class="ibtn" id="cVideo" aria-label="Video call" data-i18n-aria-label="call.video">' + ic('videocall') + '</button><button class="ibtn" id="cCall" aria-label="Voice call" data-i18n-aria-label="call.voice">' + ic('call') + '</button><button class="ibtn narrow" id="cMore" aria-label="Conversation details" data-i18n-aria-label="chat.details">' + ic('more') + '</button></div>'));
    pane.appendChild(el('<div class="msgs" id="msgs"><div class="emptyPage" role="status"><span data-i18n="chat.loading">Loading messages…</span></div></div>'));
    pane.appendChild(el('<div class="composer"><div class="ipill"><button class="ibtn" id="emojiBtn" title="Emoji" data-i18n-title="chat.emoji">' + ic('emoji') + '</button><textarea id="draft" aria-label="Message" data-i18n-aria-label="chat.message" rows="1" placeholder="Message" data-i18n-placeholder="chat.message"></textarea>'
      + '<input type="file" id="attachFile" hidden><button class="ibtn" id="attachBtn" title="Attach" data-i18n-title="chat.attach">' + ic('attach') + '</button><input type="file" id="photoFile" accept="image/*" hidden><button class="ibtn" id="photoBtn" title="Photo" data-i18n-title="chat.photo">' + ic('camera') + '</button></div>'
      + '<button class="sendbtn" id="sendBtn" title="Send" data-i18n-title="chat.send">' + ic('send') + '</button></div><div class="senderr" id="sendErr" hidden></div>'));
    $('back').addEventListener('click', () => go('#chats'));
    $('cTheme').addEventListener('click', () => $('btnTheme').click());
    $('cVideo').addEventListener('click', () => startCall(true));
    $('cCall').addEventListener('click', () => startCall(false));
    $('cMore').addEventListener('click', () => S.openIsGroup ? groupInfo(peer) : contactInfo(peer));
    $('sendBtn').addEventListener('click', sendDraft);
    $('emojiBtn').addEventListener('click', showEmojiPicker);
    $('draft').addEventListener('keydown', draftKeydown);
    $('draft').addEventListener('input', () => { const t = $('draft'); t.style.height = 'auto'; t.style.height = Math.min(140, t.scrollHeight) + 'px'; });
    $('photoBtn').addEventListener('click', () => $('photoFile').click());
    $('photoFile').addEventListener('change', () => { const f = $('photoFile').files[0]; if (f) sendFile(f, true); $('photoFile').value = ''; });
    $('attachBtn').addEventListener('click', () => sheet('<div class="h"><span data-i18n="chat.attach">Attach</span></div><button class="btn private-pick"><span data-i18n="files.privatePick">Private file…</span></button><button class="btn ghost private-list"><span data-i18n="files.title">File transfers</span></button>', panel => {
      panel.querySelector('.private-pick').onclick = () => { closeSheet(); $('attachFile').click(); };
      panel.querySelector('.private-list').onclick = () => { closeSheet(); privateFiles.list(); };
    }));
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
    } catch (e) { if (seq !== openSeq) return; const box = $('msgs'); box.innerHTML = ''; retryCard(box, 'chat.loadFailed', e.message, () => openChat(peer)); }
    if (seq !== openSeq) return;
    const box = $('msgs');
    box.addEventListener('scroll', () => { if (box.scrollTop < 40 && !seekingMessage) loadOlder(peer); toBottomBtn(); });
    if (target) await findMessage(target, seq);
  }
  async function subtitle(peer) {
    const sub = $('csub'); if (!sub) return;
    try {
      if (S.openIsGroup) { const g = await api('group.info', { id: peer }); I.render(sub, () => tr('chat.members', { count: (g.members || []).length }) + (g.iAmAdmin ? '  ·  ' + tr('chat.youAdmin') : '')); }
      else { const c = await api('contacts.info', { key: peer }); const p = presence(c.lastSeen); I.render(sub, () => presence(c.lastSeen) || tr(c.address ? 'contacts.unreached' : 'contacts.noAddress')); sub.classList.toggle('online', isOnline(c.lastSeen)); }
    } catch (e) { I.render(sub, () => S.openIsGroup ? '' : tr('contacts.notAdded')); }
  }
  function toBottomBtn() {
    const box = $('msgs'); if (!box) return;
    const far = box.scrollHeight - box.scrollTop - box.clientHeight > 300;
    let b = $('toBottom');
    if (far && !b) { b = el('<button class="tobottom" id="toBottom" aria-label="Latest messages" data-i18n-aria-label="chat.latest">' + ic('arrowdown') + '</button>'); b.addEventListener('click', () => { box.scrollTop = box.scrollHeight; }); $('chatpane').appendChild(b); }
    if (!far && b) b.remove();
  }
  async function loadOlder(peer) {
    if (peer !== S.open || olderBusy || olderDone || !S.msgs.length) return false;
    const seq = openSeq;
    olderBusy = true;
    try {
      const r = await api('chat.conversation', { peer, limit: 100, before: Number(S.msgs[0].time) });
      if (seq !== openSeq || peer !== S.open) return;
      const more = (r.messages || []).slice().sort((a, b) => Number(a.time) - Number(b.time));
      if (!more.length) { olderDone = true; return false; }
      const box = $('msgs'), before = box.scrollHeight;
      const ids = new Set(S.msgs.map(m => m.id));
      const unique = more.filter(m => !ids.has(m.id));
      if (!unique.length) { olderDone = true; return false; }
      S.msgs = unique.concat(S.msgs); renderMsgs(false); box.scrollTop = box.scrollHeight - before;
      return true;
    } catch (e) { return false; } finally { if (seq === openSeq) olderBusy = false; }
  }
  async function findMessage(target, seq) {
    try {
      while (seq === openSeq && S.open === target.peer && !S.msgs.some(m => m.id === target.id)) {
        if (!S.msgs.length || Number(S.msgs[0].time) < target.time || !await loadOlder(target.peer)) break;
      }
      if (seq !== openSeq || S.open !== target.peer) return;
      const row = [...$('msgs').querySelectorAll('.mrow')].find(r => r.dataset.id === target.id);
      if (!row) { toast(tr('chat.messageUnavailable'), 'err'); return; }
      row.classList.add('search-hit');
      row.scrollIntoView({ block: 'center' });
      setTimeout(() => row.classList.remove('search-hit'), 5000);
      toBottomBtn();
    } finally { if (seq === openSeq) seekingMessage = false; }
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
    if (m && m.ref.startsWith('pf1:')) {
      const f = window.ParlonsFiles.parse(m.ref);
      inner += '<button class="btn ghost private-file-card">' + esc(f ? '📎 ' + f.name + ' · ' + window.ParlonsFiles.size(f.size) : tr('files.private')) + '<br><span data-i18n="files.controls">Transfer controls</span></button>';
    } else if (m) {
      const url = mediaUrl(m);
      if (m.mime.startsWith('image/') && url) inner += '<img class="pic" loading="lazy" alt="photo" src="' + esc(url) + '">';
      else if (m.mime.startsWith('audio/') && url) {
        const parts = m.caption.split('|');
        inner += '<div class="audio" data-src="' + esc(url) + '"><button class="play" title="Play" data-i18n-title="media.play">' + ic('play') + '</button><div class="wave"><canvas data-wave="' + esc(parts[1] || '') + '"></canvas><div class="atime">' + esc(parts[0] || '') + '</div></div></div>';
      } else if (url) inner += '<div class="body"><a href="' + esc(url) + '" download>' + esc(preview(e.body)) + '</a></div>';
      else inner += '<div class="body">' + esc(preview(e.body)) + '</div>';
      if (m.caption && !m.mime.startsWith('audio/')) inner += '<div class="body">' + linkText(m.caption) + '</div>';
    } else if (parseContact(e.body || '')) {
      const c = parseContact(e.body);
      inner += '<div class="ccard"><div class="cchead">' + avatar(c.key, c.name, 'm') + '<div><div class="ccname">' + (c.name ? esc(c.name) : label('contacts.unnamed')) + '</div><div class="sub"><span data-i18n="contacts.shared">Shared contact</span></div></div></div>'
        + '<div class="mono whole ccaddr">' + esc(c.address) + '</div>'
        + '<div class="ccbtns"><button class="btn sm ccadd" data-addr="' + esc(c.address) + '" data-name="' + esc(c.name) + '"><span data-i18n="contacts.add">Add contact</span></button><button class="btn sm ghost cccopy" data-addr="' + esc(c.address) + '"><span data-i18n="common.copy">Copy</span></button></div></div>';
    } else inner += '<div class="body">' + linkText(e.body || '') + '</div>';
    let meta = '<span class="message-time">' + esc(hhmm(e.time)) + '</span>';
    if (mine) meta += ' ' + (S.openIsGroup && e.delivered != null && e.state !== 'read' ? e.delivered + ' ' : '') + ticks(e.state);
    return '<div class="mrow ' + (mine ? 'mine' : 'theirs') + '" data-id="' + esc(e.id) + '"><div class="bubble">' + inner + '<div class="meta"><button class="message-actions" aria-label="Message actions" data-i18n-aria-label="chat.actions" title="Message actions" data-i18n-title="chat.actions">⋯</button> ' + meta + '</div></div></div>';
  }
  function renderMsgs(scrollToEnd) {
    const box = $('msgs'); if (!box) return;
    const atEnd = !seekingMessage && (scrollToEnd || (box.scrollHeight - box.scrollTop - box.clientHeight < 80));
    let html = '', lastDay = '';
    for (const e of S.msgs) {
      const d = dayStart(e.time);
      if (d !== lastDay) { html += '<div class="daypill"><span data-day="' + Number(e.time) + '">' + esc(dayLabel(e.time)) + '</span></div>'; lastDay = d; }
      html += bubbleHtml(e);
    }
    box.innerHTML = html;
    box.querySelectorAll('[data-day]').forEach(label => I.render(label, () => dayLabel(Number(label.dataset.day))));
    const entriesById = new Map(S.msgs.map(entry => [entry.id, entry]));
    box.querySelectorAll('.mrow').forEach(row => {
      const entry = entriesById.get(row.dataset.id);
      I.render(row.querySelector('.message-time'), () => hhmm(entry.time));
      row.querySelector('.message-actions').addEventListener('click', () => messageActions(entry, row));
      row.addEventListener('contextmenu', event => {
        event.preventDefault();
        const link = event.target.closest('a.chat-link');
        if (link) linkActions(link.href); else messageActions(entry, row);
      });
    });
    box.querySelectorAll('canvas[data-wave]').forEach(drawWave);
    box.querySelectorAll('.audio').forEach(wireAudio);
    box.querySelectorAll('.private-file-card').forEach(button => button.addEventListener('click', () => {
      const entry = S.msgs.find(e => e.id === button.closest('[data-id]').dataset.id);
      const m = entry && parseMedia(entry.body), f = m && window.ParlonsFiles.parse(m.ref);
      if (f) privateFiles.show(f.id, m.ref, S.open, S.openIsGroup); else toast(tr('files.updateRequired'), 'err');
    }));
    box.querySelectorAll('.ccadd').forEach((b) => b.addEventListener('click', async () => {
      b.disabled = true;
      try { await api('contacts.add', { address: b.dataset.addr }); toast(tr('contacts.added') + (b.dataset.name ? ': ' + b.dataset.name : '')); await loadSummaries(); }
      catch (e) { toast(e.message, 'err'); b.disabled = false; }
    }));
    box.querySelectorAll('.cccopy').forEach((b) => b.addEventListener('click', () => copy(b.dataset.addr)));
    box.querySelectorAll('img.pic').forEach((img) => {
      img.tabIndex = 0; img.setAttribute('role', 'button'); img.setAttribute('data-i18n-aria-label', 'chat.openPhoto'); I.apply(img);
      img.addEventListener('click', () => openPhotos(img.closest('.mrow').dataset.id));
    });
    if (atEnd) box.scrollTop = box.scrollHeight;
    toBottomBtn();
  }
  function copyableMessage(entry) {
    const media = parseMedia(entry.body || ''), contact = parseContact(entry.body || '');
    if (contact) return { label: tr('chat.copyContact'), key: 'chat.copyContact', text: contact.address };
    if (media) return { label: tr('chat.copyCaption'), key: 'chat.copyCaption', text: media.mime.startsWith('audio/') ? '' : media.caption };
    return { label: tr('chat.copyMessage'), key: 'chat.copyMessage', text: entry.body || '' };
  }
  function messageActions(entry, row) {
    if (!entry) return;
    const item = copyableMessage(entry), links = [...new Set([...row.querySelectorAll('a.chat-link')].map(a => a.href))];
    sheet('<div class="h"><span data-i18n="chat.actions">Message actions</span></div>'
      + (item.text ? '<button class="btn full message-copy">' + label(item.key) + '</button>' : '')
      + links.map((url, index) => '<button class="btn ghost full whole message-link" data-index="' + index + '">' + esc(url) + '</button>').join('')
      + (!item.text && !links.length ? '<div class="sub"><span data-i18n="chat.noCopyText">No text to copy.</span></div>' : ''), sh => {
      const button = sh.querySelector('.message-copy');
      if (button) button.onclick = () => { closeSheet(); copy(item.text); };
      sh.querySelectorAll('.message-link').forEach(b => b.onclick = () => linkActions(links[Number(b.dataset.index)]));
    });
  }
  function linkActions(url) {
    sheet('<div class="h"><span data-i18n="link.title">Link</span></div><div class="mono whole">' + esc(url) + '</div>'
      + '<a class="btn full" href="' + esc(url) + '" target="_blank" rel="noopener noreferrer"><span data-i18n="link.open">Open link</span></a>'
      + '<button class="btn full link-copy"><span data-i18n="link.copy">Copy link</span></button><button class="btn ghost full link-share">'
      + label(navigator.share ? 'link.share' : 'link.copyToShare') + '</button>', sh => {
      sh.querySelector('.link-copy').onclick = () => { closeSheet(); copy(url); };
      sh.querySelector('.link-share').onclick = async () => {
        try { if (navigator.share) await navigator.share({ url }); else await copy(url); closeSheet(); }
        catch (e) { if (e.name !== 'AbortError') toast(tr('link.shareFailed'), 'err'); }
      };
    });
  }

  // Same conversation order and bounded navigation as the native photo viewers.
  function chatPhotos(messages) {
    const seen = new Set();
    return messages.filter((e) => {
      const m = parseMedia(e.body);
      if (!m || m.ref.startsWith('pf1:') || !m.mime.startsWith('image/') || !mediaUrl(m) || seen.has(e.id)) return false;
      seen.add(e.id); return true;
    }).slice().sort((a, b) => Number(a.time) - Number(b.time));
  }
  function openPhotos(id) {
    let photos = chatPhotos(S.msgs);
    const peer = S.open;
    let oldest = Math.min(...S.msgs.map((m) => Number(m.time)).filter((t) => t > 0));
    let olderDone = !Number.isFinite(oldest), busy = false, closed = false;
    let index = photos.findIndex((e) => e.id === id);
    if (index < 0) return;
    const focus = document.activeElement, app = $('app'), wasInert = app.inert;
    const v = el('<div class="viewer" role="dialog" aria-modal="true" aria-label="Chat photos" data-i18n-aria-label="photos.title">'
      + '<div class="photo-stage"></div><div class="photo-top"><span class="photo-count" aria-live="polite"></span>'
      + '<button class="photo-close" aria-label="Close photo viewer" data-i18n-aria-label="photos.close">×</button></div>'
      + '<div class="photo-nav"><button class="photo-prev" aria-label="Previous photo" data-i18n-aria-label="photos.previous">‹</button>'
      + '<button class="photo-next" aria-label="Next photo" data-i18n-aria-label="photos.next">›</button></div></div>');
    const stage = v.querySelector('.photo-stage'), prev = v.querySelector('.photo-prev'), next = v.querySelector('.photo-next');
    const close = () => { closed = true; document.removeEventListener('keydown', onKey, true); v.remove(); app.inert = wasInert; if (focus && focus.isConnected) focus.focus(); };
    const bind = () => {
      const img = document.createElement('img'); I.attribute(img, 'alt', 'photos.number', { number: index + 1 }); img.draggable = false;
      const note = document.createElement('button'); note.className = 'photo-status'; I.bind(note, 'photos.loading'); note.disabled = true;
      img.addEventListener('load', () => note.remove());
      img.addEventListener('error', () => { img.hidden = true; I.bind(note, 'photos.failed'); note.disabled = false; });
      note.addEventListener('click', bind);
      // Replace the element per page: a late response cannot overwrite the selected photo.
      stage.replaceChildren(img, note); img.src = mediaUrl(parseMedia(photos[index].body));
      I.text(v.querySelector('.photo-count'), (index + 1) + ' / ' + photos.length);
      prev.disabled = index === 0 && olderDone; next.disabled = index === photos.length - 1;
    };
    const page = async (delta) => {
      if (busy) return;
      const n = index + delta;
      if (n >= 0 && n < photos.length) { index = n; bind(); return; }
      if (delta >= 0 || olderDone) return;
      busy = true; prev.disabled = true; next.disabled = true;
      I.bind(v.querySelector('.photo-count'), 'photos.earlier');
      const selectedId = photos[index].id;
      try {
        while (!closed) {
          const r = await api('chat.conversation', {peer, limit: 100, before: oldest});
          if (closed) return;
          const entries = r.messages || [];
          const earliest = Math.min(...entries.map((e) => Number(e.time)).filter((t) => t > 0));
          if (!entries.length || !Number.isFinite(earliest) || earliest >= oldest) { olderDone = true; break; }
          oldest = earliest;
          photos = chatPhotos(photos.concat(entries));
          index = photos.findIndex((e) => e.id === selectedId);
          if (index > 0) { index--; break; }
        }
      } catch (e) { toast(tr('photos.earlierFailed'), 'err'); }
      finally { busy = false; if (!closed) bind(); }
    };
    prev.addEventListener('click', () => page(-1)); next.addEventListener('click', () => page(1));
    v.querySelector('.photo-close').addEventListener('click', close);
    const onKey = (e) => {
      if (e.key === 'Escape') { e.preventDefault(); close(); }
      else if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') { e.preventDefault(); page(e.key === 'ArrowLeft' ? -1 : 1); }
      else if (e.key === 'Tab') {
        const fields = [...v.querySelectorAll('button')].filter((b) => !b.disabled);
        const first = fields[0], last = fields[fields.length - 1];
        if (!v.contains(document.activeElement)) { e.preventDefault(); first.focus(); }
        else if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      }
    };
    document.addEventListener('keydown', onKey, true);
    let down = null, multi = false;
    const pointers = new Set();
    stage.addEventListener('pointerdown', (e) => {
      pointers.add(e.pointerId);
      if (pointers.size > 1) { multi = true; return; }
      multi = false; down = { x: e.clientX, y: e.clientY };
      stage.setPointerCapture(e.pointerId);
    });
    stage.addEventListener('pointerup', (e) => {
      pointers.delete(e.pointerId);
      if (down && !multi && (!window.visualViewport || window.visualViewport.scale <= 1.01)) {
        const dx = e.clientX - down.x, dy = e.clientY - down.y;
        if (Math.abs(dx) > 56 && Math.abs(dx) > Math.abs(dy) * 1.5) page(dx < 0 ? 1 : -1);
      }
      down = null;
    });
    stage.addEventListener('pointercancel', (e) => { pointers.delete(e.pointerId); down = null; multi = true; });
    document.body.appendChild(v); app.inert = true; bind(); v.querySelector('.photo-close').focus();
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
  // Same categories and glyphs as ChatActivity / ChatsPanel, without a network dependency.
  const EMOJI = [["emoji.smileys", "😀 😂 🤣 😊 😇 😉 😍 🥰 😘 😜 🤪 🤔 🤐 😐 🙄 😬 😴 🥵 🥶 🤯 😳 🥺 😢 😭 😡 🤬 🤢 🥳 😎 🤓 🙈 🙉 🙊 💀 🤡 💩"], ["emoji.gestures", "👍 👎 👌 ✌️ 🤞 🤟 🤘 👊 ✊ 👏 🙌 👐 🤲 🤝 🙏 💪 ☝️ 👆 👇 👈 👉 🖐️ 🤙 👋"], ["emoji.hearts", "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💋 💌"], ["emoji.nature", "🐶 🐱 🐭 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐵 🐔 🐧 🦆 🦅 🦉 🦋 🐝 🐢 🐙 🐳 🐬 🌵 🌲 🌻 🌹 🍂 ☀️ 🌙 ⭐ 🌈 ⚡ 🔥 ❄️ 🌊"], ["emoji.food", "🍎 🍌 🍇 🍓 🍋 🥑 🍕 🍔 🍟 🌭 🌮 🍜 🍣 🍦 🍰 🍫 🍿 ☕ 🍺 🍷 🥂 🍵"], ["emoji.objects", "🎉 🎁 🎈 ⚽ 🏀 🎸 🎮 🎲 🚗 ✈️ 🚀 ⛵ 🏠 💡 🔑 💰 💎 ⏰ 📱 💻 🎧 📷 ✅ ❌ ❗ ❓ 💯 🎖️ 🏆 🚩"]];
  function insertEmoji(draft, glyph) {
    const start = draft.selectionStart, end = draft.selectionEnd;
    draft.setRangeText(glyph, start, end, 'end');
    draft.dispatchEvent(new Event('input', { bubbles: true }));
  }
  function showEmojiPicker() {
    const draft = $('draft');
    if (!draft) return;
    sheet('<div class="h"><span data-i18n="chat.emoji">Emoji</span></div>' + EMOJI.map(([name, glyphs]) =>
      '<div class="emoji-category">' + label(name) + '</div><div class="emoji-grid">' + glyphs.split(' ').map(glyph =>
        '<button class="emoji-choice" aria-label="' + esc(glyph) + '">' + esc(glyph) + '</button>').join('') + '</div>').join(''), sh => {
      sh.querySelectorAll('.emoji-choice').forEach(button => button.onclick = () => {
        if (draft.isConnected) insertEmoji(draft, button.textContent);
      });
    });
    sheetFocus = draft;
  }

  async function sendDraft() {
    const peer = S.open, seq = openSeq; if (!peer) return;
    const body = $('draft').value.trim(); if (!body) return;
    $('draft').value = ''; $('draft').style.height = 'auto';
    const local = { id: 'local-' + Date.now() + Math.random().toString(36).slice(2), body, mine: true, time: Date.now(), state: 'sending' };
    if (seq === openSeq) { S.msgs.push(local); renderMsgs(true); }
    try { await api('chat.send', { peer, body }); await reloadOpenTail(peer, seq); }
    catch (e) { local.state = 'failed'; if (seq === openSeq) { renderMsgs(false); showSendErr(e.message); } }
  }
  function showSendErr(msg) { const b = $('sendErr'); if (!b) return; I.render(b, () => I.system(msg)); b.hidden = false; setTimeout(() => { b.hidden = true; }, 6000); }
  async function reloadOpenTail(peer, seq = openSeq) {
    if (peer !== S.open || seq !== openSeq) return;
    const r = await api('chat.conversation', { peer, limit: 30 });
    if (peer !== S.open || seq !== openSeq) return;
    const tail = r.messages || [];
    S.msgs = S.msgs.filter((m) => !String(m.id).startsWith('local-') || !tail.some((t) => t.mine && (t.body === m.body || (parseMedia(m.body) && parseMedia(t.body) && Number(t.time) >= Number(m.time) - 60000))));
    for (const t of tail) { const i = S.msgs.findIndex((x) => x.id === t.id); if (i >= 0) S.msgs[i] = t; else S.msgs.push(t); }
    S.msgs.sort((a, b) => Number(a.time) - Number(b.time));
    renderMsgs(true);
  }
  async function sendFile(file, asPhoto) {
    const peer = S.open, seq = openSeq, isGroup = S.openIsGroup; if (!peer) return;
    if (!asPhoto) { await privateFiles.send(file, peer, isGroup); return; }
    let blob = file, mime = file.type || 'application/octet-stream';
    if (asPhoto || mime.startsWith('image/')) {
      try {
        const bmp = await createImageBitmap(file);
        const max = 1600, scale = Math.min(1, max / Math.max(bmp.width, bmp.height));
        const c = document.createElement('canvas'); c.width = Math.round(bmp.width * scale); c.height = Math.round(bmp.height * scale);
        c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
        const photo = await new Promise((res) => c.toBlob(res, 'image/jpeg', 0.85)); bmp.close();
        if (photo) { blob = photo; mime = 'image/jpeg'; }
      } catch (e) { /* not an image after all: send as is */ }
    }
    if (blob.size > 16 * 1024 * 1024) { toast(tr('files.tooLarge'), 'err'); return; }
    let bytes;
    try { bytes = new Uint8Array(await blob.arrayBuffer()); } catch (e) { toast(tr('files.readFailed'), 'err'); return; }
    if (!bytes.length || bytes.length > 16 * 1024 * 1024) { toast(!bytes.length ? tr('files.empty') : tr('files.tooLarge'), 'err'); return; }
    const caption = mime.startsWith('image/') ? '' : (file.name || '');
    const local = { id: 'local-' + Date.now(), body: MEDIA_MARK + mime + '\u0001data:' + mime + ';base64,' + b64(bytes) + '\u0001' + caption, mine: true, time: Date.now(), state: 'sending' };
    if (seq === openSeq) { S.msgs.push(local); renderMsgs(true); }
    const tid = 'p' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8), CH = 48 * 1024;
    try {
      for (let off = 0; off < bytes.length; off += CH) {
        const last = off + CH >= bytes.length;
        const p = { tid, off, data: b64(bytes.subarray(off, Math.min(bytes.length, off + CH))), last };
        if (last) { p.peer = peer; p.group = isGroup; p.mime = mime; p.caption = caption; }
        await api('media.up', p);
      }
      setTimeout(() => reloadOpenTail(peer, seq).catch(() => {}), 2500);
      setTimeout(() => reloadOpenTail(peer, seq).catch(() => {}), 12000);
    } catch (e) { local.state = 'failed'; if (seq === openSeq) { renderMsgs(false); showSendErr(e.message); } }
  }
  function b64(u8) { let s = ''; for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000)); return btoa(s); }

  // ---------- contact / group sheets ----------
  // Pick one conversation/contact (not the one being shared) and send it a body.
  async function pickContactAndSend(body, exceptKey) {
    let list = (S.summaries || []).map((x) => ({ peer: x.peer, name: x.name, group: !!x.group }));
    try { const r = await api('contacts.list', { offset: 0, limit: 200 }); for (const c of (r.contacts || [])) if (!list.some((x) => x.peer === c.key)) list.push({ peer: c.key, name: c.name, group: false }); } catch (e) { }
    list = list.filter((x) => x.peer !== exceptKey);
    if (!list.length) { toast(tr('contacts.noneToSend'), 'err'); return; }
    let html = '<div class="h"><span data-i18n="contacts.sendHeading">Send contact to…</span></div>';
    for (const x of list) html += '<div role="button" tabindex="0" class="conv pick" data-peer="' + esc(x.peer) + '">' + avatar(x.peer, x.name, 'm') + '<div class="mid"><div class="name">' + esc(x.name || x.peer) + '</div>' + (x.group ? '<div class="prev"><span data-i18n="chat.group">Group</span></div>' : '') + '</div></div>';
    sheet(html, (sh) => {
      sh.querySelectorAll('.pick').forEach((row) => row.addEventListener('click', async () => {
        const peer = row.dataset.peer; closeSheet();
        try { await api('chat.send', { peer, body }); toast(tr('contacts.sent')); } catch (e) { toast(e.message, 'err'); }
      }));
    });
  }
  async function contactInfo(key) {
    let c; try { c = await api('contacts.info', { key }); } catch (e) { toast(e.message, 'err'); return; }
    sheet('<div style="display:flex;align-items:center;gap:12px">' + avatar(key, c.name, 'l') + '<div><div class="h">' + (c.name ? esc(c.name) : label('contacts.unnamed')) + '</div><div class="sub">' + esc(presence(c.lastSeen) || tr('contacts.unreached')) + '</div></div></div>'
      + '<div class="inner"><div class="sub"><span data-i18n="contacts.key">Key</span></div><div class="mono whole">' + esc(c.key || key) + '</div></div>'
      + '<div class="inner"><div class="sub"><span data-i18n="common.address">Address</span></div><div class="mono whole">' + esc(c.address || '') + '</div></div>'
      + '<div class="frow"><input class="field" id="ciName" value="' + esc(c.name || '') + '" placeholder="Name" data-i18n-placeholder="common.name"><button class="btn sm" id="ciRename"><span data-i18n="common.rename">Rename</span></button></div>'
      + '<div class="inner"><div class="sub"><span data-i18n="contacts.share">Share this contact</span></div><div class="mono whole" style="font-size:11px">' + esc(c.share || c.address || '') + '</div>'
      + '<div class="frow" style="margin-top:8px"><button class="btn sm" id="ciCopy"><span data-i18n="contacts.copyAddress">Copy address</span></button><button class="btn sm ghost" id="ciSend"><span data-i18n="contacts.sendTo">Send to a contact…</span></button></div></div>'
      + '<button class="btn ghost full" id="ciChat"><span data-i18n="contacts.openChat">Open chat</span></button><button class="btn ghost full" id="ciResolve"><span data-i18n="contacts.resolve">Re-resolve their address</span></button><button class="btn ghost full danger" id="ciRemove"><span data-i18n="contacts.remove">Remove contact</span></button>', (sh) => {
        I.render(sh.querySelector('.sub'), () => presence(c.lastSeen) || tr('contacts.unreached'));
        sh.querySelector('#ciChat').addEventListener('click', () => { closeSheet(); go('#chat/' + encodeURIComponent(key)); });
        sh.querySelector('#ciCopy').addEventListener('click', () => copy(c.share || c.address || ''));
        sh.querySelector('#ciSend').addEventListener('click', () => { closeSheet(); pickContactAndSend(wrapContact(c.key || key, c.name || '', c.share || c.address || ''), key); });
        sh.querySelector('#ciRename').addEventListener('click', async () => { try { await api('contacts.rename', { key, name: sh.querySelector('#ciName').value.trim() }); toast(tr('common.renamed')); closeSheet(); await loadSummaries(); if (S.route === 'contacts') renderContacts(); if (S.open === key) openChat(key); } catch (e) { toast(e.message, 'err'); } });
        sh.querySelector('#ciResolve').addEventListener('click', async () => { try { const r = await api('contacts.resolve', { key }); toast(r.updated ? tr('contacts.refreshed') : tr('contacts.noNewRecord')); } catch (e) { toast(e.message, 'err'); } });
        sh.querySelector('#ciRemove').addEventListener('click', async () => { if (!confirm(tr('contacts.removeConfirm'))) return; try { await api('contacts.remove', { key }); closeSheet(); toast(tr('common.removed')); await loadSummaries(); if (S.open === key) $('back').click(); if (S.route === 'contacts') renderContacts(); } catch (e) { toast(e.message, 'err'); } });
      });
  }
  async function groupInfo(id) {
    let g; try { g = await api('group.info', { id }); } catch (e) { toast(e.message, 'err'); return; }
    const rows = (g.members || []).map((m) => '<div class="rowitem">' + avatar(m.key, m.name, 's') + '<div class="mid"><div class="n">' + esc(m.name || '') + (m.me ? ' ' + label('chat.you') : '') + (m.admin ? ' · ' + label('chat.admin') : '') + '</div><div class="s mono">' + esc(m.key) + '</div></div></div>').join('');
    sheet('<div class="h">' + esc(g.name || '') + '</div><div class="sub">' + label('chat.members', { count: (g.members || []).length }) + '</div>'
      + (g.iAmAdmin ? '<div class="frow"><input class="field" id="giName" value="' + esc(g.name || '') + '"><button class="btn sm" id="giRename"><span data-i18n="common.rename">Rename</span></button></div>' : '<div class="sub"><span data-i18n="chat.groupAdminOnly">Only an admin can change the group.</span></div>')
      + '<div style="margin-top:8px">' + rows + '</div>', (sh) => {
        const rn = sh.querySelector('#giRename');
        if (rn) rn.addEventListener('click', async () => { try { await api('group.update', { id, name: sh.querySelector('#giName').value.trim() }); toast(tr('common.renamed')); closeSheet(); await loadSummaries(); openChat(id); } catch (e) { toast(e.message, 'err'); } });
      });
  }

  // ---------- Contacts page (page_contacts: identity card, search, rows) ----------
  async function renderContacts() {
    const page = $('page'); page.innerHTML = '';
    const body = el('<div class="pagebody"></div>'); page.appendChild(body);
    const idc = el('<div class="card"><div style="display:flex;align-items:center;gap:12px">' + avatar(S.me.permanent, S.me.name, 'l') + '<div style="flex:1;min-width:0"><div class="h">' + (S.me.name ? esc(S.me.name) : label('contacts.owner')) + '</div><div class="sub"><span data-i18n="contacts.permanentHelp">Your address never changes. Share it as a QR or copy it.</span></div></div></div>'
      + '<div class="frow"><button class="btn sm" id="myQrBtn">' + ic('qr') + label('contacts.showQr') + '</button><button class="btn ghost sm" id="myCopy">' + ic('copy') + label('contacts.copyAddress') + '</button></div><div class="qr" id="myQr" hidden></div></div>');
    idc.querySelector('#myCopy').addEventListener('click', () => copy(S.me.permanent));
    idc.querySelector('#myQrBtn').addEventListener('click', () => { const q = idc.querySelector('#myQr'); q.hidden = !q.hidden; if (!q.hidden) drawQr(q, S.me.permanent); });
    body.appendChild(idc);
    const add = el('<div class="card"><div class="h"><span data-i18n="contacts.addHeading">Add a contact</span></div><div class="sub"><span data-i18n="contacts.pasteHelp">Paste their address (MAX#… or Mx…). They get an introduction; the chat opens once they accept.</span></div><div class="frow"><input class="field" id="addAddr" placeholder="Paste an address" data-i18n-placeholder="contacts.pasteAddress"><button class="btn sm" id="addBtn">' + ic('personadd') + label('common.add') + '</button></div></div>');
    add.querySelector('#addBtn').addEventListener('click', async () => { const address = add.querySelector('#addAddr').value.trim(); if (!address) return; try { await api('contacts.add', { address }); toast(tr('contacts.introduced')); add.querySelector('#addAddr').value = ''; setTimeout(() => { if (S.route === 'contacts') renderContacts(); }, 1500); } catch (e) { toast(e.message, 'err'); } });
    body.appendChild(add);
    const list = el('<div class="card"><input class="field" id="cSearch" placeholder="Search contacts" data-i18n-placeholder="contacts.search" style="margin-bottom:6px"><div id="cRows"></div></div>');
    body.appendChild(list);
    try { S.contacts = (await api('contacts.list', { offset: 0, limit: 200 })).contacts || []; } catch (e) { toast(e.message, 'err'); }
    const draw = () => {
      const q = list.querySelector('#cSearch').value.trim().toLowerCase();
      const rows = list.querySelector('#cRows'); rows.innerHTML = '';
      const cs = S.contacts.filter((c) => !q || (c.name || '').toLowerCase().includes(q) || (c.key || '').toLowerCase().includes(q));
      if (!cs.length) rows.innerHTML = '<div class="sub">' + label(q ? 'chat.noMatches' : 'contacts.empty') + '</div>';
      for (const c of cs) {
        const p = presence(c.lastSeen);
        const r = el('<div class="rowitem" role="button" tabindex="0" style="cursor:pointer">' + avatar(c.key, c.name, 'm') + '<div class="mid"><div class="n">' + (c.name ? esc(c.name) : label('contacts.unnamed')) + '</div><div class="s">' + esc(p || tr('contacts.unreached')) + '</div></div>' + (isOnline(c.lastSeen) ? '<span class="spill ok"><span class="dot"></span><span data-i18n="presence.online">online</span></span>' : '') + '</div>');
        I.render(r.querySelector('.s'), () => presence(c.lastSeen) || tr('contacts.unreached'));
        r.addEventListener('click', () => contactInfo(c.key));
        rows.appendChild(r);
      }
    };
    list.querySelector('#cSearch').addEventListener('input', draw); draw();
  }
  function drawQr(host, text) { if (!text) { host.textContent = ''; return; } try { const q = qrcode(0, 'M'); q.addData(text); q.make(); host.innerHTML = q.createImgTag(3, 0); } catch (e) { host.textContent = ''; } }
  async function copy(text) {
    try { await navigator.clipboard.writeText(text); toast(tr('common.copied')); }
    catch (e) { const ta = document.createElement('textarea'); ta.value = text; document.body.appendChild(ta); ta.select(); document.execCommand('copy'); ta.remove(); toast(tr('common.copied')); }
  }

  async function renderAdminPassword(body, pairingInput, newCode) {
    let state;
    try { state = await apiGet('admin-password'); } catch (_) { return; }
    if (!state.available) return;
    const card = el('<form class="card"><div class="h" id="passwordTitle"></div><div class="sub"><span data-i18n="password.purpose">Protect invitations that give another device access to your owner account and node controls.</span></div>'
      + '<label id="currentPasswordLabel"><span data-i18n="password.current">Current password</span><input class="field" id="currentPassword" type="password" autocomplete="current-password" maxlength="1024"></label>'
      + '<label><span data-i18n="password.new">New password</span><input class="field" id="newPassword" type="password" autocomplete="new-password" minlength="10" maxlength="1024" required></label>'
      + '<label><span data-i18n="password.confirm">Confirm new password</span><input class="field" id="confirmPassword" type="password" autocomplete="new-password" minlength="10" maxlength="1024" required></label>'
      + '<div class="sub"><span data-i18n="password.lengthHelp">Use at least 10 characters.</span></div><div class="frow"><button class="btn sm" type="submit" id="savePassword"><span data-i18n="password.save">Save password</span></button></div><div class="sub" id="passwordResult" role="status"></div></form>');
    const current = card.querySelector('#currentPassword'), password = card.querySelector('#newPassword'), confirmation = card.querySelector('#confirmPassword');
    const save = card.querySelector('#savePassword'), result = card.querySelector('#passwordResult');
    function update() {
      I.bind(card.querySelector('#passwordTitle'), state.configured ? 'password.change' : 'password.set');
      card.querySelector('#currentPasswordLabel').hidden = !state.configured;
      current.required = !!state.configured;
      pairingInput.hidden = !state.configured;
      newCode.disabled = !state.configured;
      if (state.configured) { newCode.removeAttribute('data-i18n-title'); newCode.title = ''; }
      else { newCode.setAttribute('data-i18n-title', 'password.required'); I.apply(newCode); }
    }
    card.addEventListener('submit', async event => {
      event.preventDefault();
      if (save.disabled) return;
      if (password.value.length < 10 || password.value !== confirmation.value) {
        I.bind(result, password.value.length < 10 ? 'password.lengthHelp' : 'password.mismatch'); return;
      }
      save.disabled = true; I.bind(result, 'common.saving');
      const request = { currentPassword: current.value, newPassword: password.value, confirmation: confirmation.value };
      current.value = password.value = confirmation.value = '';
      try {
        await api('admin-password', request);
        state.configured = true; update(); I.bind(result, 'password.saved');
      } catch (e) { I.render(result, () => I.system(e.message)); }
      finally { request.currentPassword = request.newPassword = request.confirmation = ''; save.disabled = false; }
    });
    update(); body.appendChild(card);
  }

  async function renderHostedAccounts(body) {
    const card = el('<div class="card"><div class="h"><span data-i18n="hosting.title">Hosted accounts</span></div><div class="sub" id="hostingInfo"></div>'
      + '<div class="frow"><button class="btn ghost sm" id="hostingRefresh"><span data-i18n="common.refresh">Refresh</span></button><button class="btn sm" id="hostingToggle" hidden></button></div>'
      + '<form id="hostingCreate"><label><span data-i18n="hosting.accountName">Account name</span><input class="field" id="tenantName" placeholder="e.g. alice" data-i18n-placeholder="hosting.nameExample" pattern="[A-Za-z0-9][A-Za-z0-9._-]{0,63}" maxlength="64" required></label><button class="btn sm" type="submit"><span data-i18n="hosting.create">Create account</span></button></form>'
      + '<div id="hostingRows"></div><div class="sub" role="status" id="hostingResult"></div><div id="tenantInvitation" hidden><div class="qr" id="tenantQr"></div><div class="mono whole" id="tenantInviteText"></div><button class="btn ghost sm" id="tenantCopy"><span data-i18n="hosting.copyInvite">Copy tenant invite</span></button></div></div>');
    body.appendChild(card);
    let busy = false, enabled = false;
    const result = card.querySelector('#hostingResult'), invitation = card.querySelector('#tenantInvitation');
    async function action(operation, name = '') {
      if (busy) return;
      busy = true; I.bind(result, 'common.loading'); invitation.hidden = true;
      card.querySelector('#tenantInviteText').textContent = ''; card.querySelector('#tenantQr').innerHTML = '';
      card.querySelector('#tenantCopy').onclick = null;
      try {
        const reply = await api('hosted.accounts', { action: operation, name });
        I.text(result, '');
        if (operation === 'invite') {
          if (!reply.invite) throw new Error(tr('hosting.notReady'));
          invitation.hidden = false; drawQr(card.querySelector('#tenantQr'), reply.invite);
          card.querySelector('#tenantInviteText').textContent = reply.invite;
          card.querySelector('#tenantCopy').onclick = () => copy(reply.invite);
          I.bind(result, 'hosting.inviteHelp', { name });
          return;
        }
        enabled = !!reply.enabled;
        const online = !!reply.online;
        I.bind(card.querySelector('#hostingInfo'), reply.access === 'chat-only' ? 'hosting.chatAccess' : 'hosting.fullAccess');
        const toggle = card.querySelector('#hostingToggle'); toggle.hidden = !reply.managed;
        I.bind(toggle, enabled ? 'hosting.stop' : 'hosting.enable');
        card.querySelector('#hostingCreate').hidden = !online;
        const rows = card.querySelector('#hostingRows'); rows.innerHTML = '';
        for (const account of reply.accounts || []) {
          const row = el('<div class="rowitem hosted-account-row"><div class="mid"><div class="n"></div><div class="s"></div></div><button class="btn sm" id="tenantInvite"><span data-i18n="hosting.showInvite">Show invitation</span></button><button class="btn ghost sm" id="tenantPause"></button></div>');
          row.querySelector('.n').textContent = account.name;
          const stateKey = { offline: 'hosting.stateOffline', pausing: 'hosting.statePausing', paused: 'hosting.statePaused', running: 'hosting.stateRunning', retrying: 'hosting.stateRetrying', starting: 'hosting.stateStarting' }[account.state];
          if (stateKey) I.bind(row.querySelector('.s'), stateKey); else I.text(row.querySelector('.s'), account.state);
          const invite = row.querySelector('#tenantInvite'); invite.disabled = !online || account.state !== 'running';
          invite.onclick = () => action('invite', account.name);
          const pause = row.querySelector('#tenantPause'); pause.disabled = !online;
          I.bind(pause, account.paused ? 'common.resume' : 'common.pause');
          pause.onclick = () => { if (account.paused || confirm(tr('hosting.pauseConfirm', { name: account.name }))) action(account.paused ? 'resume' : 'pause', account.name); };
          rows.appendChild(row);
        }
        if (operation === 'create') I.bind(result, 'hosting.created');
        else if (!online) I.bind(result, reply.managed ? (enabled ? 'hosting.starting' : 'hosting.stopped') : 'hosting.offline');
      } catch (e) { I.render(result, () => I.system(e.message)); }
      finally { busy = false; }
    }
    card.querySelector('#hostingRefresh').onclick = () => action('list');
    card.querySelector('#hostingToggle').onclick = () => {
      if (confirm(tr(enabled ? 'hosting.stopConfirm' : 'hosting.enableConfirm'))) action(enabled ? 'disable' : 'enable');
    };
    card.querySelector('#hostingCreate').addEventListener('submit', async event => {
      event.preventDefault(); const input = card.querySelector('#tenantName');
      const name = input.value.trim(); if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(name)) { I.bind(result, 'hosting.nameRules'); return; }
      await action('create', name);
    });
    await action('list');
  }

  // ---------- Devices page ----------
  async function renderDevices() {
    const page = $('page'); page.innerHTML = '';
    const body = el('<div class="pagebody"></div>'); page.appendChild(body);
    const pair = el('<div class="card"><div class="h"><span data-i18n="pairing.owner">Pair your own device</span></div><div class="sub"><span data-i18n="pairing.ownerHelp">This gives access to this account. Use Hosted accounts below to invite someone to a separate account.</span></div><div class="sub" id="invText"><span data-i18n="pairing.noCode">No pairing code is outstanding.</span></div><div class="qr" id="invQr" hidden></div><div class="mono whole" id="invTxt" style="font-size:11px;margin-top:6px"></div><div class="frow"><button class="btn sm" id="newCode"><span data-i18n="pairing.newCode">New pairing code</span></button><button class="btn ghost sm" id="copyInv" hidden>' + ic('copy') + label('pairing.copyInvite') + '</button></div></div>');
    body.appendChild(pair);
    const adminInput = document.createElement('input'); adminInput.type = 'password';
    adminInput.setAttribute('data-i18n-placeholder', 'pairing.adminPassword'); I.apply(adminInput); adminInput.autocomplete = 'off'; adminInput.hidden = true;
    pair.insertBefore(adminInput, pair.querySelector('.frow'));
    let hostStatus = {};
    try { hostStatus = await api('node.status'); adminInput.hidden = !hostStatus.adminPairingPassword; }
    catch (e) { /* old servers keep their existing invitation behavior */ }
    await renderAdminPassword(body, adminInput, pair.querySelector('#newCode'));
    if (hostStatus.hostedAccounts) await renderHostedAccounts(body);
    else if (hostStatus.hostingError) body.appendChild(el('<div class="card"><div class="h"><span data-i18n="hosting.title">Hosted accounts</span></div><div class="sub">' + esc(hostStatus.hostingError) + '</div></div>'));
    const devs = el('<div class="card"><div class="h"><span data-i18n="nav.devices">Devices</span></div><div id="devRows"></div></div>'); body.appendChild(devs);
    async function refreshInvite() {
      try {
        const inv = await apiGet('invite');
        const q = pair.querySelector('#invQr');
        if (inv.invite) { I.bind(pair.querySelector('#invText'), 'pairing.scanHelp'); q.hidden = false; drawQr(q, inv.invite); pair.querySelector('#invTxt').textContent = inv.invite; pair.querySelector('#copyInv').hidden = false; pair.querySelector('#copyInv').onclick = () => copy(inv.invite); }
        else { I.bind(pair.querySelector('#invText'), 'pairing.noCode'); q.hidden = true; pair.querySelector('#invTxt').textContent = ''; pair.querySelector('#copyInv').hidden = true; }
      } catch (e) { }
    }
    async function refreshDevices() {
      try {
        const d = await api('pair.list'); const rows = devs.querySelector('#devRows'); rows.innerHTML = '';
        for (const dev of (d.authorized || [])) {
          const r = el('<div class="rowitem"><div class="mid"><div class="n">' + esc(dev.label) + (dev.local ? ' <span class="spill"><span data-i18n="pairing.thisComputer">this computer</span></span>' : '') + '</div><div class="s mono">' + esc(dev.key) + '</div></div><button class="btn ghost sm danger"><span data-i18n="common.revoke">Revoke</span></button></div>');
          r.querySelector('button').addEventListener('click', async () => { if (!confirm(tr('pairing.revokeConfirm'))) return; try { await api('pair.revoke', { device: dev.key }); toast(tr('common.revoked')); refreshDevices(); } catch (e) { toast(e.message, 'err'); } });
          rows.appendChild(r);
        }
        for (const k of (d.pending || [])) {
          const r = el('<div class="rowitem"><div class="mid"><div class="n"><span data-i18n="pairing.waiting">Waiting for approval</span></div><div class="s mono">' + esc(k) + '</div></div><button class="btn sm"><span data-i18n="common.approve">Approve</span></button></div>');
          r.querySelector('button').addEventListener('click', async () => { try { await api('pair.approve', { device: k }); toast(tr('common.approved')); refreshDevices(); } catch (e) { toast(e.message, 'err'); } });
          rows.appendChild(r);
        }
        if (!rows.children.length) rows.innerHTML = '<div class="sub"><span data-i18n="common.none">none</span></div>';
      } catch (e) { toast(e.message, 'err'); }
    }
    pair.querySelector('#newCode').addEventListener('click', async () => { try { const adminPassword = adminInput.value; adminInput.value = ''; await api('pair.newcode', { adminPassword }); toast(tr('pairing.created')); setTimeout(refreshInvite, 900); } catch (e) { toast(e.message, 'err'); } });
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
    try { st = await api('node.status'); fig = await api('node.figures'); }
    catch (e) { retryCard(body, 'node.loadFailed', e.message, renderNode); return; }
    if (!body.isConnected) return;
    const up = Number(st.uptime || 0), h = Math.floor(up / 3600000), m = Math.floor((up % 3600000) / 60000);
    const anchor = (st.permanent || '').split('@').pop();
    const ownAnchor = !!fig.ownRelay && anchor === fig.ownRelay;
    body.appendChild(el('<div class="card"><div class="ctitle2"><span data-i18n="node.account">Account</span></div>'
      + '<div class="metric"><span class="k"><span data-i18n="common.name">Name</span></span><span class="v">' + esc(st.name) + '</span></div>'
      + '<div class="metric"><span class="k"><span data-i18n="common.version">Version</span></span><span class="v">' + esc(st.version) + '</span></div>'
      + '<div class="metric"><span class="k"><span data-i18n="node.uptime">Up</span></span><span class="v">' + label('node.uptimeValue', { hours: h, minutes: m }) + '</span></div>'
      + '<div class="metric"><span class="k"><span data-i18n="node.relaysAttached">Relays attached</span></span><span class="v">' + esc(st.hosts) + '</span></div>'
      + '<div class="metric"><span class="k"><span data-i18n="node.meshPeers">Mesh peers</span></span><span class="v">' + esc(st.meshPeers) + '</span></div>'
      + '<div class="metric"><span class="k"><span data-i18n="node.pairedDevices">Paired devices</span></span><span class="v">' + esc(st.pairedDevices) + '</span></div>'
      + '<div class="metric"><span class="k"><span data-i18n="node.mailboxOutbox">Mailbox / outbox</span></span><span class="v">' + esc(fig.mailboxHeld == null ? '' : fig.mailboxHeld) + ' / ' + esc(fig.outbox == null ? '' : fig.outbox) + '</span></div>'
      + '<div class="inner"><div class="sub">' + label(ownAnchor ? 'node.permanentOwn' : 'node.permanentFleet', ownAnchor ? {} : { relay: anchor }) + '</div><div class="mono whole" style="font-size:11px;margin-top:4px">' + esc(st.permanent) + '</div></div>'
      + (fig.directAddress ? '<div class="inner"><div class="sub"><span data-i18n="node.directAddress">Direct address</span></div><div class="mono whole" style="font-size:11px">' + esc(fig.directAddress) + '</div></div>' : '') + '</div>'));
    // Your own relay (the cape): what it is, whether the world can reach it, what it carries.
    const rs = fig.ownRelayState || (st.relayOn ? 'nohost' : 'off');
    const relayKey = { off: 'node.relayOff', nohost: 'node.relayNoHost', verified: 'node.relayVerified', unreachable: 'node.relayUnreachable', attached: 'node.relayAttached' }[rs] || 'node.attaching';
    const relayLine = label(relayKey, relayKey === 'node.relayUnreachable' ? { port: String(fig.ownRelay || '').split(':').pop() } : {});
    const stateKey = { off: 'node.off', nohost: 'node.noPublicAddress', verified: 'node.verified', unreachable: 'node.unreachable', attached: 'node.attached' }[rs];
    body.appendChild(el('<div class="card"><div class="ctitle2"><span data-i18n="node.relay">Your relay</span></div>'
      + '<div class="metric"><span class="k"><span data-i18n="common.address">Address</span></span><span class="v mono">' + (fig.ownRelay ? esc(fig.ownRelay) : st.relayOn ? label('node.noPublicAddress') : '—') + '</span></div>'
      + '<div class="metric"><span class="k"><span data-i18n="common.state">State</span></span><span class="v"><span class="spill' + (rs === 'verified' ? ' ok' : rs === 'unreachable' ? ' bad' : '') + '"><span class="dot"></span>' + (stateKey ? label(stateKey) : esc(rs)) + '</span></span></div>'
      + '<div class="metric"><span class="k"><span data-i18n="node.connections">Connections</span></span><span class="v">' + esc(fig.relayConnections == null ? '—' : fig.relayConnections) + '</span></div>'
      + '<div class="metric"><span class="k"><span data-i18n="node.relayedStored">Relayed / stored</span></span><span class="v">' + esc(fig.relayRelayed == null ? '—' : fig.relayRelayed) + ' / ' + esc(fig.relayStored == null ? '—' : fig.relayStored) + '</span></div>'
      + '<div class="sub" style="margin-top:8px">' + relayLine + '</div></div>'));
    // Port forwarding: exactly what to type into the router, and whether it worked.
    if (rs !== 'off' && fig.relayPort) {
      const open = fig.portOpen === 'true' ? true : fig.portOpen === 'false' ? false : null;
      const verdict = open === true ? '<span class="spill ok"><span class="dot"></span><span data-i18n="node.reached">Reached from the internet ✓</span></span>'
        : open === false ? '<span class="spill bad"><span class="dot"></span>' + label(fig.lanIp ? 'node.forwardNeeded' : 'node.forwardNeededLocal', fig.lanIp ? { port: fig.relayPort, host: fig.lanIp } : { port: fig.relayPort }) + '</span>'
        : '<span class="spill"><span class="dot"></span><span data-i18n="node.waitingInbound">Waiting for the first connection from the internet…</span></span>';
      body.appendChild(el('<div class="card"><div class="ctitle2"><span data-i18n="node.portForwarding">Port forwarding</span></div>'
        + '<div class="sub"><span data-i18n="node.routerHelp">Your contacts reach this relay through your router. One rule, once:</span></div>'
        + '<div class="metric"><span class="k"><span data-i18n="node.protocol">Protocol</span></span><span class="v">TCP</span></div>'
        + '<div class="metric"><span class="k"><span data-i18n="node.externalPort">External port</span></span><span class="v mono">' + esc(fig.relayPort) + '</span></div>'
        + '<div class="metric"><span class="k"><span data-i18n="node.internalPort">Internal port</span></span><span class="v mono">' + esc(fig.relayPort) + '</span></div>'
        + '<div class="metric"><span class="k"><span data-i18n="node.sendTo">Send to</span></span><span class="v mono">' + (fig.lanIp ? esc(fig.lanIp) : label('pairing.thisComputer')) + '</span></div>'
        + (fig.gatewayIp ? '<div class="metric"><span class="k"><span data-i18n="node.router">Your router</span></span><span class="v"><a class="mono" href="http://' + esc(fig.gatewayIp) + '/" target="_blank" rel="noopener">http://' + esc(fig.gatewayIp) + '/</a></span></div>' : '')
        + (fig.publicIp ? '<div class="metric"><span class="k"><span data-i18n="node.publicAddress">Your public address</span></span><span class="v mono">' + esc(fig.publicIp) + ':' + esc(fig.relayPort) + '</span></div>' : '')
        + '<div style="margin-top:8px">' + verdict + '</div>'
        + '<div class="sub" style="margin-top:8px">' + label(fig.lanIp ? 'node.fixedAddress' : 'node.fixedAddressLocal', fig.lanIp ? { host: fig.lanIp } : {}) + '</div></div>'));
    }
    const hosts = el('<div class="card"><div class="ctitle2"><span data-i18n="node.relays">Relays</span></div><div id="hostRows"></div><div class="frow"><input class="field" id="hostAdd" placeholder="host:port, or a relay QR text" data-i18n-placeholder="node.hostPlaceholder"><button class="btn sm" id="hostAddBtn"><span data-i18n="common.add">Add</span></button></div><div class="sw"><div class="lbl"><span data-i18n="node.builtinList">Use the built-in relay list</span><small><span data-i18n="node.builtinHelp">One seed source among several; switch it off once you have relays of your own.</span></small></div><button class="switch' + (fig.builtin ? ' on' : '') + '" id="builtin"></button></div></div>');
    const hr = hosts.querySelector('#hostRows');
    for (const hh of (fig.hosts || [])) {
      const r = el('<div class="rowitem"><span class="spill' + (hh.connected ? ' ok' : '') + '"><span class="dot"></span>' + label(hh.connected ? 'node.connected' : 'node.notAttached') + '</span><div class="mid"><div class="s mono">' + esc(hh.host) + '</div></div>' + (hh.builtin ? '<span class="spill"><span data-i18n="node.builtin">built-in</span></span>' : '<button class="btn ghost sm danger"><span data-i18n="common.remove">Remove</span></button>') + '</div>');
      const rb = r.querySelector('button'); if (rb) rb.addEventListener('click', async () => { try { await api('node.hosts', { remove: hh.host }); toast(tr('node.detached')); renderNode(); } catch (e) { toast(e.message, 'err'); } });
      hr.appendChild(r);
    }
    hosts.querySelector('#hostAddBtn').addEventListener('click', async () => { const v = hosts.querySelector('#hostAdd').value.trim(); if (!v) return; try { await api('node.hosts', { add: v }); toast(tr('connection.connecting')); setTimeout(() => { if (S.route === 'node') renderNode(); }, 1500); } catch (e) { toast(e.message, 'err'); } });
    wireSwitch(hosts.querySelector('#builtin'), 'node.builtinList', (on) => api('node.hosts', { builtin: on }), (on) => tr(on ? 'node.builtinOn' : 'node.builtinOff'));
    body.appendChild(hosts);
    const mls = el('<div class="card"><div class="ctitle2"><span data-i18n="node.anchor">Directory anchor</span></div><div class="sub"><span data-i18n="node.anchorHelp">Where your permanent address resolves. Pin it to one relay you trust, or let the account choose.</span></div><div class="frow"><button class="btn ghost sm" id="mlsPin"><span data-i18n="node.pinRelay">Pin best relay</span></button><button class="btn ghost sm" id="mlsClear"><span data-i18n="common.clear">Clear</span></button><button class="btn ghost sm" id="mlsRepub"><span data-i18n="node.republish">Republish</span></button></div><div class="mono whole" id="mlsInfo" style="font-size:11px;margin-top:6px"></div></div>');
    async function mlsDo(action) { try { const r = await api('node.mls', { action }); I.bind(mls.querySelector('#mlsInfo'), r.pinned ? 'node.pinned' : 'node.auto', { relay: r.mls || '' }); toast(tr('common.done')); } catch (e) { toast(e.message, 'err'); } }
    mls.querySelector('#mlsPin').addEventListener('click', () => mlsDo('pin')); mls.querySelector('#mlsClear').addEventListener('click', () => mlsDo('clear')); mls.querySelector('#mlsRepub').addEventListener('click', () => mlsDo('republish'));
    body.appendChild(mls);
    const log = el('<div class="card"><div class="ctitle2"><span data-i18n="node.log">Log</span></div><div class="log" id="nodeLog">…</div><div class="frow"><button class="btn ghost sm" id="logRefresh"><span data-i18n="common.refresh">Refresh</span></button><button class="btn ghost sm" id="logClear"><span data-i18n="common.clear">Clear</span></button></div></div>');
    async function loadLog(clear) { try { const r = await api('node.log', clear ? { clear: true } : {}); if ((r.lines || []).length) I.text(log.querySelector('#nodeLog'), r.lines.join('\n')); else I.bind(log.querySelector('#nodeLog'), 'node.emptyLog'); } catch (e) { toast(e.message, 'err'); } }
    log.querySelector('#logRefresh').addEventListener('click', () => loadLog(false)); log.querySelector('#logClear').addEventListener('click', () => loadLog(true));
    body.appendChild(log); loadLog(false);
  }

  // ---------- Settings page ----------
  async function renderSettings() {
    const page = $('page'); page.innerHTML = '';
    const body = el('<div class="pagebody"></div>'); page.appendChild(body);
    const language = el('<div class="card"><div class="ctitle2" data-i18n="language.title">Language</div><div class="sub" data-i18n="language.description">Choose the language for this device.</div><button class="btn ghost full language-change" data-i18n-aria-label="language.change">' + ic('language') + 'English · 简体中文 · Русский</button></div>');
    language.querySelector('button').addEventListener('click', () => I.chooser()); body.appendChild(language);
    let s; try { s = await api('settings.get'); } catch (e) { retryCard(body, 'settings.loadFailed', e.message, renderSettings); return; }
    if (!body.isConnected) return;
    const name = el('<div class="card"><div class="ctitle2"><span data-i18n="settings.profile">Profile</span></div><div class="h"><span data-i18n="settings.yourName">Your name</span></div><div class="sub"><span data-i18n="settings.nameHelp">What your contacts see.</span></div><div class="frow"><input class="field" id="setName" value="' + esc(S.me.name) + '"><button class="btn sm" id="saveName"><span data-i18n="common.save">Save</span></button></div></div>');
    name.querySelector('#saveName').addEventListener('click', async () => { const v = name.querySelector('#setName').value.trim(); if (!v) return; try { await api('identity.setname', { name: v }); S.me.name = v; toast(tr('common.saved')); } catch (e) { toast(e.message, 'err'); } });
    body.appendChild(name);
    const docs = el('<div class="card"><div class="ctitle2"><span data-i18n="settings.connectedApps">Connected apps</span></div><div class="h">minimaDocs</div><div class="sub"><span data-i18n="settings.docsHelp">View, add and remove contacts and exchange document invitations with this account.</span></div><div class="sub" id="docsAccount"></div><div class="sub" id="docsStatus"></div><div class="frow"><button class="btn sm" id="docsConnect"><span data-i18n="settings.docsConnect">Connect minimaDocs</span></button><button class="btn ghost sm" id="docsRevoke"><span data-i18n="settings.docsRevoke">Revoke minimaDocs</span></button></div><textarea class="field" id="docsLink" readonly hidden aria-label="minimaDocs connection link" data-i18n-aria-label="settings.docsLink"></textarea><div class="sub" id="docsHint"></div></div>');
    I.render(docs.querySelector('#docsAccount'), () => tr('settings.docsAccount', { name: S.me.name || tr('settings.account') }));
    const docsLink = docs.querySelector('#docsLink'); let expiry;
    async function docsStatus() { try { const r = await api('minimadocs/status'); if (docs.isConnected) I.bind(docs.querySelector('#docsStatus'), 'settings.docsConnections', { count: r.connections }); } catch (e) { I.render(docs.querySelector('#docsStatus'), () => I.system(e.message)); } }
    docs.querySelector('#docsConnect').addEventListener('click', async () => {
      if (!confirm(tr('settings.docsConfirm', { name: S.me.name || tr('settings.account') }))) return;
      try { const r = await api('minimadocs/approve'); if (!docs.isConnected) return; docsLink.value = r.link; docsLink.hidden = false; docsLink.select(); I.bind(docs.querySelector('#docsHint'), 'settings.docsCopyHelp'); clearTimeout(expiry); expiry = setTimeout(() => { docsLink.value = ''; docsLink.hidden = true; I.bind(docs.querySelector('#docsHint'), 'settings.docsExpired'); docsStatus(); }, 120000); } catch (e) { toast(e.message, 'err'); }
    });
    docs.querySelector('#docsRevoke').addEventListener('click', async () => {
      if (!confirm(tr('settings.docsDisconnect'))) return;
      try { await api('minimadocs/revoke'); clearTimeout(expiry); docsLink.value = ''; docsLink.hidden = true; I.bind(docs.querySelector('#docsHint'), 'settings.docsRevoked'); docsStatus(); } catch (e) { toast(e.message, 'err'); }
    });
    body.appendChild(docs); docsStatus();
    const priv = el('<div class="card"><div class="ctitle2"><span data-i18n="settings.privacy">Privacy</span></div><div class="sw"><div class="lbl"><span data-i18n="settings.readReceipts">Read receipts</span><small><span data-i18n="settings.receiptsHelp">Your contacts see when you have read their messages.</span></small></div><button class="switch' + (s.readReceipts ? ' on' : '') + '" id="rr"></button></div></div>');
    wireSwitch(priv.querySelector('#rr'), 'settings.readReceipts', (on) => api('settings.set', { readReceipts: on }), () => tr('common.saved'));
    body.appendChild(priv);
    const th = el('<div class="card"><div class="ctitle2"><span data-i18n="settings.appearance">Appearance</span></div><div class="sub"><span data-i18n="settings.themeHelp">Theme follows the button in the top bar: system, light, dark.</span></div></div>');
    body.appendChild(th);
    const ab = el('<div class="card"><div class="ctitle2"><span data-i18n="settings.panel">This panel</span></div><div class="sub"><span data-i18n="settings.panelHelp">Signed in as this computer, a local device of the account. The seed, the wallet and the node console stay on your paired phones and the command line.</span></div><div class="frow"><button class="btn ghost sm" id="openBrowser"><span data-i18n="common.openBrowser">Open in a browser</span></button><button class="btn ghost sm" id="signOut"><span data-i18n="common.signOut">Sign out</span></button></div><div class="sub" style="margin-top:10px">Parlons ' + esc(S.version) + ' · Powered by Maxima</div></div>');
    ab.querySelector('#openBrowser').addEventListener('click', async () => { try { const r = await api('ticket'); window.open(r.url, '_blank'); } catch (e) { toast(e.message, 'err'); } });
    ab.querySelector('#signOut').addEventListener('click', async () => { try { await api('logout'); } catch (e) {} location.reload(); });
    body.appendChild(ab);
  }

  async function refreshPill() {
    try { const st = await api('node.status'); S.version = st.version || ''; $('ver').textContent = S.version ? 'v' + S.version : ''; const n = Number(st.hosts || 0); setState(n ? 'connection.relays' : 'connection.noRelays', n > 0 ? 'ok' : 'bad', { count: n }); }
    catch (e) { setState('connection.offline', 'bad'); }
  }

  function startCall(video) {
    if (S.openIsGroup) { toast(tr('call.oneToOne')); return; }
    if (calls && S.open) calls.start(S.open, S.openName, video).catch(e => toast(I.system(e.message), 'err'));
  }

  // ---------- live events ----------
  let es = null, calls = null;
  function listen() {
    if (es) { try { es.close(); } catch (e) {} }
    es = new EventSource('/events?client=' + encodeURIComponent(calls.client));
    es.addEventListener('hello', () => { calls.ready = true; refreshPill(); catchUp(); });
    es.addEventListener('push', (ev) => { let e; try { e = JSON.parse(ev.data); } catch (x) { return; } handleEvent(e); });
    es.onerror = () => { calls.ready = false; setState('connection.reconnecting', 'bad'); };
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
      if (calls) calls.receive(e);
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
  function draftKeydown(e) {
    // Enter confirms a Chinese/Japanese composition before it can send a message.
    if (e.isComposing || e.keyCode === 229) return;
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendDraft(); }
  }
  async function boot() {
    I.apply(); if (!I.hasChoice()) I.chooser(true);
    $('page').innerHTML = '<div class="emptyPage" role="status"><span data-i18n="connection.account">Connecting to your account…</span></div>';
    try {
      const p = await api('ping');
      S.me = { name: p.name || '', permanent: p.permanent || '', primary: p.primary || '' };
      await refreshPill();
    } catch (e) { setState('connection.offline', 'bad'); $('page').innerHTML = ''; retryCard($('page'), tr('connection.unreachable'), e.message, boot); return; }
    await loadSummaries().catch((e) => toast(e.message, 'err'));
    if ('Notification' in window && Notification.permission === 'default') { try { Notification.requestPermission(); } catch (e) {} }
    if (!calls) calls = window.initParlonsCalls({api, avatar, toast});
    listen();
    render();
  }
  boot();
})();
