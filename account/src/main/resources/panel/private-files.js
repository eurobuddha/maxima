/* Private transfer controls shared by the node panel and both Electron hosts. */
(function (root) {
  'use strict';
  const MAX = 100 * 1024 * 1024, LEGACY_MAX = 512 * 1024 * 1024, BLOCK = 48 * 1024;
  function parse(ref) {
    if (typeof ref !== 'string' || !ref.startsWith('pf1:') || ref.length > 65536) return null;
    try {
      let b = ref.slice(4).replace(/-/g, '+').replace(/_/g, '/'); while (b.length % 4) b += '=';
      const f = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(b), c => c.charCodeAt(0))));
      if (f.v !== '1' || !/^[a-f0-9]{32}$/.test(f.id) || typeof f.name !== 'string'
          || !f.name || f.name === '.' || f.name === '..' || /[\x00-\x1f\x7f/\\]/.test(f.name) || f.name.length > 240 || !Number.isSafeInteger(Number(f.size)) || Number(f.size) < 0 || Number(f.size) > LEGACY_MAX) return null;
      return f;
    } catch (_) { return null; }
  }
  function size(n) { n = Number(n) || 0; return n < 1024 ? n + ' B' : n < 1048576 ? (n / 1024).toFixed(1) + ' KB' : (n / 1048576).toFixed(1) + ' MB'; }
  function create({ api, sheet, closeSheet, esc, toast }) {
    const I = root.ParlonsI18n, tr = I.t;
    const pendingUploads = new Map();
    async function call(action, params) { const r = await api('files', { action, ...params }); if (r.ok === false || r.ok === 'false') throw Error(r.error || 'Transfer failed'); return r; }
    function show(id, ref, peer, group) {
      const offer = parse(ref); let exists = false, paused = false, busy = false, finished = false;
      const panel = sheet('<div class="h">' + esc(offer ? offer.name : tr('files.private')) + '</div>'
        + '<p class="sub" data-i18n="files.description">Encrypted file sharing with this conversation. Active downloads can also upload encrypted pieces.</p>'
        + '<p class="file-status" aria-live="polite">' + (offer ? esc(size(offer.size)) : esc(tr('files.preparing'))) + '</p>'
        + '<progress class="file-progress" max="100" value="0" style="width:100%"></progress>'
        + '<label class="file-keep-label" hidden><input type="checkbox" class="file-keep"><span data-i18n="files.keepSharing">Keep sharing</span></label>'
        + '<div class="actions"><button class="btn file-action" data-i18n="files.download">Download</button><a class="btn ghost file-save" data-i18n="files.save" hidden>Save file</a>'
        + '<button class="btn ghost danger file-remove" data-i18n="files.remove" hidden>Remove local transfer</button></div>');
      const status = panel.querySelector('.file-status'), action = panel.querySelector('.file-action'), save = panel.querySelector('.file-save');
      const remove = panel.querySelector('.file-remove'), keep = panel.querySelector('.file-keep');
      let current = null, nextReceipt = 0;
      async function tick() {
        if (!panel.isConnected) return;
        try {
          const s = await call('status', { id }); if (!panel.isConnected) return;
          current = s; id = s.fileId || id;
          if (Date.now() >= nextReceipt) {
            nextReceipt = Date.now() + 15000;
            const pending = localStorage.getItem('parlons-file-receipt/' + id);
            if (pending) try { await call('deviceReceived', {id, digest: pending}); localStorage.removeItem('parlons-file-receipt/' + id); } catch (_) {}
          } exists = true; paused = s.paused === 'true' || s.status === 'Failed';
          I.render(status, () => I.system(s.status) + (s.delivery ? '\n' + I.system(s.delivery) : '') + (s.deliveryError ? '\n' + s.deliveryError : '') + (s.error ? '\n' + I.system(s.error) : '') + '\n' + size(s.done) + ' / ' + size(s.size));
          panel.querySelector('progress').value = 100 * Number(s.done || 0) / Math.max(1, Number(s.size || 0));
          finished = s.ready === 'true'; save.hidden = !finished; save.href = '/private-file?id=' + encodeURIComponent(id);
          save.setAttribute('download', s.name || 'Parlons file');
          const preparing = s.status === 'Preparing' || s.status === 'Uploading';
          panel.querySelector('.file-keep-label').hidden = s.mine !== 'true'; keep.checked = s.keepSharing === 'true';
          I.bind(action, paused ? 'files.resume' : 'files.pause'); if (s.sharingStopped === 'true' && s.mine === 'true') I.bind(action, 'files.shareAgain'); action.disabled = busy || preparing; remove.hidden = preparing;
        } catch (e) { if (!exists) I.render(status, () => offer ? tr('files.ready', { size: size(offer.size) }) : I.system(e.message)); }
        if (panel.isConnected) setTimeout(tick, 1500);
      }
      action.onclick = async () => {
        if (busy) return; busy = true; action.disabled = true;
        try { if (!exists) { if (!offer) throw Error('Transfer unavailable'); if (Number(offer.size) > MAX) throw Error(tr('files.limit')); await call('download', { ref, peer, group }); }
          else await call(paused ? 'resume' : 'pause', { id });
        } catch (e) { toast(I.system(e.message), 'err'); } finally { busy = false; action.disabled = false; }
      };
      keep.onchange = async () => { try { await call('keepSharing', { id, keep: String(keep.checked) }); } catch(e) { toast(I.system(e.message), 'err'); } };
      save.onclick = async event => {
        event.preventDefault(); if (!current || busy) return;
        busy = true;
        try {
          const response = await fetch('/private-file?id=' + encodeURIComponent(id), { credentials: 'same-origin', redirect: 'error' });
          if (!response.ok || Number(response.headers.get('content-length')) !== Number(current.size) || Number(current.size) > LEGACY_MAX) throw Error('Download interrupted');
          const bytes = await response.arrayBuffer();
          const digest = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), n => n.toString(16).padStart(2, '0')).join('');
          if (bytes.byteLength !== Number(current.size) || digest !== current.digest) throw Error('Saved file verification failed');
          const url = URL.createObjectURL(new Blob([bytes])), link = document.createElement('a');
          link.href = url; link.download = current.name; link.click(); setTimeout(() => URL.revokeObjectURL(url), 60000);
          localStorage.setItem('parlons-file-receipt/' + id, digest);
          await call('deviceReceived', { id, digest }); localStorage.removeItem('parlons-file-receipt/' + id);
        } catch(e) { toast(I.system(e.message), 'err'); } finally { busy = false; }
      };
      remove.onclick = async () => { if (!confirm(tr('files.removeConfirm'))) return;
        try { await call('remove', { id }); closeSheet(); } catch (e) { toast(I.system(e.message), 'err'); } };
      tick();
    }
    function updateUpload(u) {
      if (!u.panel || !u.panel.isConnected) return;
      I.render(u.panel.querySelector('.file-status'), () => u.error
        ? tr('files.uploadPausedDetail', { error: I.system(u.error) }) : tr('files.uploadingAccount'));
      u.panel.querySelector('progress').value = 100 * u.offset / Math.max(1, u.file.size);
      const resume = u.panel.querySelector('.resume');
      resume.hidden = !u.error; resume.disabled = u.running;
    }
    function showUpload(u) {
      u.panel = sheet('<div class="h"></div><p class="sub file-status" aria-live="polite"></p>'
        + '<progress max="100" value="0" style="width:100%"></progress><div class="actions"><button class="btn resume" data-i18n="files.resumeUpload" hidden>Resume upload</button>'
        + '<button class="btn ghost cancel" data-i18n="files.cancelUpload">Cancel upload</button><button class="btn ghost hide" data-i18n="files.hide">Hide</button></div>');
      I.bind(u.panel.querySelector('.h'), 'files.sending', { name: u.file.name });
      u.panel.querySelector('.resume').onclick = () => runUpload(u);
      u.panel.querySelector('.cancel').onclick = () => { u.cancel = true; if (!u.running) return runUpload(u); };
      u.panel.querySelector('.hide').onclick = closeSheet;
      updateUpload(u);
    }
    async function uploadCall(u, action, params) {
      // begin, append and finish are already idempotent on the host. A lost reply
      // must retry the same ID and offset, including when the host accepted the bytes.
      for (let attempt = 0; ; attempt++) {
        if (u.cancel) throw Error('Upload cancelled');
        try { return await call(action, params); }
        catch (e) {
          if (attempt === 2) throw e;
          await new Promise(resolve => setTimeout(resolve, 250 * (attempt + 1)));
        }
      }
    }
    async function runUpload(u) {
      if (u.running) return;
      u.running = true; u.error = ''; updateUpload(u);
      const { id, file, peer, group } = u;
      try {
        const begin = await uploadCall(u, 'begin', { id, name: file.name, mime: file.type || 'application/octet-stream', size: String(file.size), peer, group });
        const offset = Number(begin.offset);
        if (!Number.isSafeInteger(offset) || offset < 0 || offset > file.size) throw Error('Invalid upload offset');
        u.offset = offset; updateUpload(u);
        while (u.offset < file.size) {
          if (u.cancel) throw Error('Upload cancelled');
          const bytes = new Uint8Array(await file.slice(u.offset, u.offset + BLOCK).arrayBuffer());
          let binary = ''; for (const b of bytes) binary += String.fromCharCode(b);
          const reply = await uploadCall(u, 'append', { id, offset: String(u.offset), data: btoa(binary) });
          const next = Number(reply.offset);
          if (next !== u.offset + bytes.length) throw Error('Invalid upload offset');
          u.offset = next; updateUpload(u);
        }
        await uploadCall(u, 'finish', { id });
        pendingUploads.delete(id);
        if (u.panel.isConnected) { closeSheet(); show(id, null, peer, group); } else toast(tr('files.preparingNotice'));
      } catch (e) {
        if (u.cancel) {
          try { await call('cancelUpload', { id }); } catch (_) {}
          pendingUploads.delete(id); if (u.panel.isConnected) closeSheet();
        } else {
          u.error = e.message || 'Connection interrupted';
          toast(tr('files.pausedNotice'), 'err');
        }
      } finally { u.running = false; updateUpload(u); }
    }
    async function send(file, peer, group) {
      if (file.size > MAX) { toast(tr('files.limit'), 'err'); return; }
      const id = Array.from(crypto.getRandomValues(new Uint8Array(16)), n => n.toString(16).padStart(2, '0')).join('');
      const u = { id, file, peer, group, offset: 0, running: false, cancel: false, error: '', panel: null };
      pendingUploads.set(id, u); showUpload(u); await runUpload(u);
    }
    async function list() {
      try { const transfers = JSON.parse((await call('list')).transfers);
        const panel = sheet('<div class="h" data-i18n="files.title">File transfers</div><div class="file-list"></div>');
        if (!transfers.length && !pendingUploads.size) I.bind(panel.querySelector('.file-list'), 'files.empty');
        pendingUploads.forEach(u => {
          const b = document.createElement('button'); b.className = 'btn ghost';
          I.render(b, () => u.file.name + ' · ' + tr(u.running ? 'files.uploading' : 'files.paused'));
          b.onclick = () => { closeSheet(); showUpload(u); }; panel.querySelector('.file-list').appendChild(b);
        });
        transfers.filter(f => !pendingUploads.has(f.id)).forEach(f => { const b = document.createElement('button'); b.className = 'btn ghost'; I.render(b, () => f.name + ' · ' + I.system(f.status));
          b.onclick = () => { closeSheet(); show(f.id); }; panel.querySelector('.file-list').appendChild(b); });
      } catch (e) { toast(I.system(e.message), 'err'); }
    }
    return { show, send, list };
  }
  root.ParlonsFiles = { parse, size, create };
  if (typeof module !== 'undefined') module.exports = { parse, size };
})(typeof window !== 'undefined' ? window : globalThis);
