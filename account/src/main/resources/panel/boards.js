/* Boards: post once, read by many (core/board). The many-member group that never fans out.
 * Shared by the node panel and the Electron hosts; wired by app.js with its helpers. */
(function (root) {
  'use strict';
  const POLL_MS = 15000;
  function create({ api, sheet, closeSheet, esc, toast, el, go, ic, avatar, drawQr, listTime, label, $, copy, linkText, parseMedia, mediaUrl, b64 }) {
    const I = root.ParlonsI18n, tr = I.t;
    const S = { boards: [], open: null, board: null, posts: [], replyTo: null, timer: null, seq: 0 };

    async function load() {
      try { S.boards = (await api('board.list')).boards || []; } catch (e) { /* strip shows what it has */ }
      return S.boards;
    }

    // ---------- the strip on the Chats page ----------
    function strip(host) {
      const box = el('<div class="boards" id="boards"></div>');
      host.appendChild(box);
      draw(box);
      load().then(() => draw(box));
    }
    function draw(box) {
      box = box || $('boards'); if (!box || !box.isConnected) return;
      box.innerHTML = '';
      const head = el('<div class="bhead"><span class="h"><span data-i18n="board.title">Boards</span></span><span class="acts"><button class="btn sm ghost" id="bJoin">' + ic('scan') + label('board.join') + '</button><button class="btn sm" id="bNew">' + ic('plus') + label('board.new') + '</button></span></div>');
      head.querySelector('#bJoin').addEventListener('click', joinSheet);
      head.querySelector('#bNew').addEventListener('click', newSheet);
      box.appendChild(head);
      if (!S.boards.length) { box.appendChild(el('<div class="sub bempty"><span data-i18n="board.empty">No boards yet. Make one, or join one from a link.</span></div>')); return; }
      for (const b of S.boards) {
        const row = el('<div role="button" tabindex="0" class="conv' + (S.open === b.id ? ' on' : '') + '">' + avatar(b.id, b.name, 'l')
          + '<div class="mid"><div class="name">' + esc(b.name || tr('board.untitled')) + '</div><div class="prev">' + esc(subtitle(b)) + '</div></div>'
          + '<div class="right"><div class="time">' + esc(b.lastActivity ? listTime(b.lastActivity) : '') + '</div>' + (b.unread && S.open !== b.id ? '<div class="badge">' + Number(b.unread) + '</div>' : '') + '</div></div>');
        row.addEventListener('click', () => go('#board/' + encodeURIComponent(b.id)));
        box.appendChild(row);
      }
    }
    function subtitle(b) {
      if (b.joining) return tr('board.waiting');
      if (b.removed) return tr('board.removedYou');
      return tr(b.open ? 'board.openMembers' : 'board.inviteMembers', { count: Number(b.members || 0) });
    }

    // ---------- make / join ----------
    function newSheet() {
      sheet('<div class="h"><span data-i18n="board.new">New board</span></div><div class="sub"><span data-i18n="board.newHelp">A board is for many people: everyone on it posts and replies, nothing is sent to anyone. Up to thousands of members.</span></div>'
        + '<div class="frow"><input class="field" id="bName" placeholder="Board name" data-i18n-placeholder="board.name"></div>'
        + '<label class="brow"><input type="checkbox" id="bOpen" checked> <span data-i18n="board.openLabel">Anyone with the link can join</span></label>'
        + '<div class="sub"><span data-i18n="board.openHelp">Off: people ask to join and an admin lets them in.</span></div>'
        + '<button class="btn full" id="bCreate"><span data-i18n="board.create">Create board</span></button>', (sh) => {
          sh.querySelector('#bCreate').addEventListener('click', async () => {
            const name = sh.querySelector('#bName').value.trim();
            if (!name) { toast(tr('board.nameRequired'), 'err'); return; }
            try {
              const r = await api('board.create', { name, open: sh.querySelector('#bOpen').checked });
              closeSheet(); toast(tr('board.created'));
              await load(); draw();
              go('#board/' + encodeURIComponent(r.board.id));
              setTimeout(() => linkSheet(r.board, r.link), 300);
            } catch (e) { toast(e.message, 'err'); }
          });
        });
    }
    function joinSheet() {
      sheet('<div class="h"><span data-i18n="board.joinTitle">Join a board</span></div><div class="sub"><span data-i18n="board.joinHelp">Paste the board link you were given (parlons://board/…). An open link lets you in at once; an invite-only one asks the admin.</span></div>'
        + '<div class="frow"><input class="field" id="bLink" placeholder="parlons://board/…" autocomplete="off"><button class="btn sm" id="bJoinGo"><span data-i18n="board.joinButton">Join</span></button></div>', (sh) => {
          sh.querySelector('#bJoinGo').addEventListener('click', async () => {
            const link = sh.querySelector('#bLink').value.trim();
            if (!link) return;
            try {
              const r = await api('board.join', { link });
              closeSheet(); toast(tr(r.board.joining ? 'board.knocked' : 'board.joined'));
              await load(); draw();
              go('#board/' + encodeURIComponent(r.board.id));
            } catch (e) { toast(e.message, 'err'); }
          });
        });
    }
    function linkSheet(b, link) {
      sheet('<div class="h">' + esc(b.name || tr('board.untitled')) + '</div><div class="sub"><span data-i18n="' + (b.open ? 'board.linkOpenHelp' : 'board.linkInviteHelp') + '">' + esc(tr(b.open ? 'board.linkOpenHelp' : 'board.linkInviteHelp')) + '</span></div>'
        + '<div class="qr" id="bQr"></div><div class="mono blink">' + esc(link) + '</div>'
        + '<button class="btn full" id="bCopy">' + ic('copy') + label('board.copyLink') + '</button>', (sh) => {
          drawQr(sh.querySelector('#bQr'), link);
          sh.querySelector('#bCopy').addEventListener('click', () => copy(link));
        });
    }

    // ---------- the board screen ----------
    function current() { return S.boards.find((b) => b.id === S.open) || S.board; }
    async function open(id) {
      const seq = ++S.seq;
      closeSheet(); stopPolling();
      S.open = id; S.posts = []; S.replyTo = null;
      if (!S.boards.length) await load();
      S.board = S.boards.find((b) => b.id === id) || null;
      draw();
      $('app').classList.add('chat');
      const pane = $('chatpane'); pane.innerHTML = '';
      const name = S.board ? (S.board.name || tr('board.untitled')) : tr('board.untitled');
      pane.appendChild(el('<div class="cbar"><button class="ibtn back" id="back" aria-label="Back to chats" data-i18n-aria-label="chat.back">' + ic('back') + '</button>' + avatar(id, name, 'm')
        + '<div class="titles"><div class="ctitle" id="bTitle">' + esc(name) + '</div><div class="csub" id="bSub">' + esc(S.board ? subtitle(S.board) : '') + '</div></div>'
        + '<button class="ibtn narrow" id="bMore" aria-label="Board details" data-i18n-aria-label="board.details">' + ic('more') + '</button></div>'));
      pane.appendChild(el('<div class="msgs" id="bPosts"><div class="emptyPage" role="status"><span data-i18n="board.loading">Loading the board…</span></div></div>'));
      pane.appendChild(el('<div class="rpill" id="bReply" hidden><span id="bReplyTo"></span><button class="ibtn" id="bReplyX" aria-label="Cancel reply" data-i18n-aria-label="board.cancelReply">' + ic('close') + '</button></div>'));
      pane.appendChild(el('<div class="composer"><button class="ibtn" id="bPhoto" title="Photo" data-i18n-title="desktopChat.photoPick">' + ic('plus') + '</button><input type="file" id="bFile" accept="image/*" hidden><div class="ipill"><textarea id="bDraft" aria-label="Post" data-i18n-aria-label="board.post" rows="1" placeholder="Write a post" data-i18n-placeholder="board.placeholder"></textarea></div>'
        + '<button class="sendbtn" id="bSend" title="Post" data-i18n-title="board.post">' + ic('send') + '</button></div><div class="senderr" id="bErr" hidden></div>'));
      I.apply(pane);
      $('back').addEventListener('click', () => go('#chats'));
      $('bMore').addEventListener('click', infoSheet);
      $('bSend').addEventListener('click', send);
      $('bReplyX').addEventListener('click', () => setReply(null));
      $('bPhoto').addEventListener('click', () => $('bFile').click());
      $('bFile').addEventListener('change', () => { const f = $('bFile').files && $('bFile').files[0]; $('bFile').value = ''; if (f) sendPhoto(f); });
      $('bDraft').addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } });
      $('bDraft').addEventListener('input', () => { const t = $('bDraft'); t.style.height = 'auto'; t.style.height = Math.min(140, t.scrollHeight) + 'px'; });
      await refresh(seq, true);
      api('board.markread', { id }).catch(() => {});
      S.timer = setInterval(() => refresh(seq, false), POLL_MS);
    }
    function stopPolling() { if (S.timer) { clearInterval(S.timer); S.timer = null; } }
    function close() { stopPolling(); S.open = null; S.board = null; S.posts = []; S.replyTo = null; }

    async function refresh(seq, scroll) {
      if (seq !== S.seq || !S.open) return;
      let r;
      try { r = await api('board.posts', { id: S.open, limit: 500 }); } catch (e) { showErr(e.message); return; }
      if (seq !== S.seq) return;
      S.board = r.board; const i = S.boards.findIndex((b) => b.id === r.board.id); if (i >= 0) S.boards[i] = r.board; else S.boards.unshift(r.board);
      const grew = (r.posts || []).length !== S.posts.length;
      S.posts = r.posts || [];
      const t = $('bTitle'), s = $('bSub'); if (t) t.textContent = r.board.name || tr('board.untitled'); if (s) s.textContent = subtitle(r.board);
      render(scroll || grew);
      if (grew) api('board.markread', { id: S.open }).catch(() => {});
    }
    function showErr(msg) { const b = $('bErr'); if (!b) return; b.textContent = msg; b.hidden = false; setTimeout(() => { b.hidden = true; }, 6000); }

    function render(scroll) {
      const box = $('bPosts'); if (!box) return;
      box.innerHTML = '';
      const b = S.board || {};
      if (b.joining) { box.appendChild(el('<div class="emptyPage" role="status"><span data-i18n="board.waitingLong">You asked to join. Posts appear once an admin lets you in.</span></div>')); I.apply(box); setComposer(false); return; }
      setComposer(b.canPost !== false);
      if (!S.posts.length) { box.appendChild(el('<div class="emptyPage" role="status"><span data-i18n="board.noPosts">Nothing posted yet. Start it off.</span></div>')); I.apply(box); return; }
      const byId = new Map(S.posts.map((p) => [p.id, p]));
      const replies = new Map();
      const tops = [];
      for (const p of S.posts) {
        if (p.re && byId.has(p.re)) { if (!replies.has(p.re)) replies.set(p.re, []); replies.get(p.re).push(p); } else tops.push(p);
      }
      for (const p of tops) {
        box.appendChild(bubble(p, false));
        const rs = replies.get(p.id) || [];
        if (rs.length) { const wrap = el('<div class="replies"></div>'); for (const r of rs) wrap.appendChild(bubble(r, true)); box.appendChild(wrap); }
      }
      if (scroll) box.scrollTop = box.scrollHeight;
    }
    function bubble(p, isReply) {
      const who = p.mine ? tr('chat.you') : (p.name || p.from);
      const m = parseMedia(p.body || '');
      let inner;
      if (m && m.mime.startsWith('image/') && mediaUrl(m) && (!m.ref.startsWith('data:') || m.ref.startsWith('data:image/'))) {
        const u = mediaUrl(m);
        inner = '<a href="' + esc(u) + '" target="_blank" rel="noopener noreferrer"><img class="pic" loading="lazy" src="' + esc(u) + '" alt=""></a>' + (m.caption ? '<div class="body">' + linkText(m.caption) + '</div>' : '');
      } else if (m) {
        inner = '<div class="body">📎 ' + esc(m.mime) + (m.caption ? ' ' + linkText(m.caption) : '') + '</div>';
      } else {
        inner = '<div class="body">' + linkText(p.body || '') + '</div>';
      }
      const row = el('<div class="mrow' + (p.mine ? ' mine' : '') + '"><div class="bubble">' + (p.mine ? '' : '<div class="sname">' + esc(who) + '</div>')
        + inner + '<div class="meta">' + esc(listTime(p.ts)) + (isReply ? '' : ' · <button class="linkbtn breply">' + esc(tr('board.reply')) + '</button>') + '</div></div></div>');
      const rb = row.querySelector('.breply'); if (rb) rb.addEventListener('click', () => setReply(p));
      return row;
    }
    function setReply(p) {
      S.replyTo = p;
      const pill = $('bReply'); if (!pill) return;
      pill.hidden = !p;
      if (p) { const m = parseMedia(p.body || ''); $('bReplyTo').textContent = tr('board.replyingTo', { name: p.mine ? tr('chat.you') : (p.name || p.from) }) + ': ' + (m ? '📷 ' + (m.caption || '') : (p.body || '')).slice(0, 80); $('bDraft').focus(); }
    }
    function setComposer(on) { const d = $('bDraft'), s = $('bSend'); if (d) d.disabled = !on; if (s) s.disabled = !on; }
    async function send() {
      const d = $('bDraft'); if (!d) return;
      const body = d.value.trim(); if (!body) return;
      const id = S.open, re = S.replyTo ? S.replyTo.id : '';
      d.value = ''; d.style.height = 'auto'; setReply(null);
      try {
        const r = await api('board.post', { id, body, re });
        if (S.open === id && r.post) { S.posts.push(r.post); render(true); }
        load().then(() => draw());
      } catch (e) { showErr(e.message); d.value = body; }
    }

    // A photo on a board: shrunk HERE (1280 px long edge, JPEG 0.82) so the board stays lean,
    // uploaded in chunks to the account, which publishes it once and posts the ref.
    async function sendPhoto(file) {
      const id = S.open, re = S.replyTo ? S.replyTo.id : '';
      if (!id) return;
      let blob = file, mime = file.type || 'image/jpeg';
      try {
        const bmp = await createImageBitmap(file);
        const max = 1280, scale = Math.min(1, max / Math.max(bmp.width, bmp.height));
        const c = document.createElement('canvas'); c.width = Math.round(bmp.width * scale); c.height = Math.round(bmp.height * scale);
        c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
        const photo = await new Promise((res) => c.toBlob(res, 'image/jpeg', 0.82)); bmp.close();
        if (photo) { blob = photo; mime = 'image/jpeg'; }
      } catch (e) { showErr(tr('files.readFailed')); return; }
      if (blob.size > 16 * 1024 * 1024) { showErr(tr('files.tooLarge')); return; }
      let bytes;
      try { bytes = new Uint8Array(await blob.arrayBuffer()); } catch (e) { showErr(tr('files.readFailed')); return; }
      setReply(null);
      const tid = 'b' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8), CH = 48 * 1024;
      try {
        for (let off = 0; off < bytes.length; off += CH) {
          const last = off + CH >= bytes.length;
          const p = { tid, off, data: b64(bytes.subarray(off, Math.min(bytes.length, off + CH))), last };
          if (last) { p.board = id; p.re = re; p.mime = mime; p.caption = ''; }
          await api('media.up', p);
        }
        toast(tr('board.post'));
        setTimeout(() => refresh(S.seq, true), 4000);
        setTimeout(() => refresh(S.seq, true), 15000);
      } catch (e) { showErr(e.message); }
    }

    // ---------- details: link + QR, knocks, members, rename, leave ----------
    async function infoSheet() {
      const b = current(); if (!b) return;
      let link = '', knocks = [];
      try { link = (await api('board.link', { id: b.id })).link || ''; } catch (e) { /* shown without */ }
      if (b.admin && !b.open) { try { knocks = (await api('board.knocks', { id: b.id })).knocks || []; } catch (e) { /* none */ } }
      const seen = new Map();
      for (const p of S.posts) if (!p.mine && !seen.has(p.from)) seen.set(p.from, p.name || p.from);
      const members = [...seen.entries()].map(([fp, name]) => '<div class="rowitem">' + avatar(fp, name, 's') + '<div class="mid"><div class="n">' + esc(name) + '</div><div class="s mono">' + esc(fp) + '</div></div>'
        + (b.admin ? '<button class="btn sm ghost bRemove" data-fp="' + esc(fp) + '"><span data-i18n="board.remove">Remove</span></button>' : '') + '</div>').join('');
      const knockRows = knocks.map((k) => '<div class="rowitem">' + avatar(k.fp, k.name, 's') + '<div class="mid"><div class="n">' + esc(k.name || k.fp) + '</div><div class="s mono">' + esc(k.address || k.fp) + '</div></div><button class="btn sm bApprove" data-fp="' + esc(k.fp) + '"><span data-i18n="board.approve">Let in</span></button></div>').join('');
      sheet('<div class="h">' + esc(b.name || tr('board.untitled')) + '</div><div class="sub">' + esc(subtitle(b)) + (b.admin ? ' · ' + esc(tr('chat.admin')) : '') + '</div>'
        + (b.admin ? '<div class="frow"><input class="field" id="biName" value="' + esc(b.name || '') + '"><button class="btn sm" id="biRename"><span data-i18n="common.rename">Rename</span></button></div>' : '')
        + (link ? '<div class="h2"><span data-i18n="board.link">Invite link</span></div><div class="sub"><span data-i18n="' + (b.open ? 'board.linkOpenHelp' : 'board.linkInviteHelp') + '">' + esc(tr(b.open ? 'board.linkOpenHelp' : 'board.linkInviteHelp')) + '</span></div><div class="qr" id="biQr"></div><div class="mono blink">' + esc(link) + '</div><button class="btn full" id="biCopy">' + ic('copy') + label('board.copyLink') + '</button>' : '')
        + (knockRows ? '<div class="h2"><span data-i18n="board.knocks">Asking to join</span></div>' + knockRows : '')
        + '<div class="h2"><span data-i18n="board.members">Members</span></div>' + (members || '<div class="sub"><span data-i18n="board.membersQuiet">Members show here once they post.</span></div>')
        + '<button class="btn ghost full danger" id="biLeave"><span data-i18n="board.leave">Leave board</span></button>', (sh) => {
          if (link) { drawQr(sh.querySelector('#biQr'), link); sh.querySelector('#biCopy').addEventListener('click', () => copy(link)); }
          const rn = sh.querySelector('#biRename');
          if (rn) rn.addEventListener('click', async () => { try { await api('board.rename', { id: b.id, name: sh.querySelector('#biName').value.trim() }); toast(tr('common.renamed')); closeSheet(); await load(); draw(); refresh(S.seq, false); } catch (e) { toast(e.message, 'err'); } });
          sh.querySelectorAll('.bApprove').forEach((btn) => btn.addEventListener('click', async () => { try { await api('board.approve', { id: b.id, fp: btn.dataset.fp }); toast(tr('board.approved')); btn.disabled = true; } catch (e) { toast(e.message, 'err'); } }));
          sh.querySelectorAll('.bRemove').forEach((btn) => btn.addEventListener('click', async () => { if (!confirm(tr('board.removeConfirm'))) return; try { await api('board.remove', { id: b.id, fp: btn.dataset.fp }); toast(tr('board.removed')); btn.disabled = true; refresh(S.seq, false); } catch (e) { toast(e.message, 'err'); } }));
          sh.querySelector('#biLeave').addEventListener('click', async () => { if (!confirm(tr('board.leaveConfirm'))) return; try { await api('board.leave', { id: b.id }); closeSheet(); toast(tr('board.left')); S.boards = S.boards.filter((x) => x.id !== b.id); go('#chats'); } catch (e) { toast(e.message, 'err'); } });
        });
    }

    return { strip, draw, open, close, load, isLink: (t) => typeof t === 'string' && t.trim().startsWith('parlons://board/') };
  }
  root.ParlonsBoards = { create };
})(window);
