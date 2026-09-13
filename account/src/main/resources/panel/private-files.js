/* Private transfer controls shared by the node panel and both Electron hosts. */
(function (root) {
  'use strict';
  const MAX = 512 * 1024 * 1024, BLOCK = 48 * 1024;
  function parse(ref) {
    if (typeof ref !== 'string' || !ref.startsWith('pf1:') || ref.length > 65536) return null;
    try {
      let b = ref.slice(4).replace(/-/g, '+').replace(/_/g, '/'); while (b.length % 4) b += '=';
      const f = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(b), c => c.charCodeAt(0))));
      if (f.v !== '1' || !/^[a-f0-9]{32}$/.test(f.id) || typeof f.name !== 'string'
          || !f.name || f.name === '.' || f.name === '..' || /[\x00-\x1f\x7f/\\]/.test(f.name) || f.name.length > 240 || !Number.isSafeInteger(Number(f.size)) || Number(f.size) < 0 || Number(f.size) > MAX) return null;
      return f;
    } catch (_) { return null; }
  }
  function size(n) { n = Number(n) || 0; return n < 1024 ? n + ' B' : n < 1048576 ? (n / 1024).toFixed(1) + ' KB' : (n / 1048576).toFixed(1) + ' MB'; }
  function create({ api, sheet, closeSheet, esc, toast }) {
    async function call(action, params) { const r = await api('files', { action, ...params }); if (r.ok === false || r.ok === 'false') throw Error(r.error || 'Transfer failed'); return r; }
    function show(id, ref, peer, group) {
      const offer = parse(ref); let exists = false, paused = false, busy = false, finished = false;
      const panel = sheet('<div class="h">' + esc(offer ? offer.name : 'Private file') + '</div>'
        + '<p class="sub">Encrypted file sharing with this conversation. Active downloads can also upload encrypted pieces.</p>'
        + '<p class="file-status" aria-live="polite">' + (offer ? esc(size(offer.size)) : 'Preparing…') + '</p>'
        + '<progress class="file-progress" max="100" value="0" style="width:100%"></progress>'
        + '<div class="actions"><button class="btn file-action">Download</button><a class="btn ghost file-save" hidden>Save file</a>'
        + '<button class="btn ghost danger file-remove" hidden>Remove local transfer</button></div>');
      const status = panel.querySelector('.file-status'), action = panel.querySelector('.file-action'), save = panel.querySelector('.file-save');
      const remove = panel.querySelector('.file-remove');
      async function tick() {
        if (!panel.isConnected) return;
        try {
          const s = await call('status', { id }); if (!panel.isConnected) return;
          id = s.fileId || id; exists = true; paused = s.paused === 'true' || s.status === 'Failed';
          status.textContent = s.status + (s.error ? '\n' + s.error : '') + '\n' + size(s.done) + ' / ' + size(s.size);
          panel.querySelector('progress').value = 100 * Number(s.done || 0) / Math.max(1, Number(s.size || 0));
          finished = s.ready === 'true'; save.hidden = !finished; save.href = '/private-file?id=' + encodeURIComponent(id);
          save.setAttribute('download', s.name || 'Parlons file');
          const preparing = s.status === 'Preparing' || s.status === 'Uploading';
          action.textContent = paused ? 'Resume' : 'Pause sharing'; action.disabled = busy || preparing; remove.hidden = preparing;
        } catch (e) { if (!exists) status.textContent = offer ? 'Ready to download · ' + size(offer.size) : e.message; }
        if (panel.isConnected) setTimeout(tick, 1500);
      }
      action.onclick = async () => {
        if (busy) return; busy = true; action.disabled = true;
        try { if (!exists) { if (!offer) throw Error('Transfer unavailable'); await call('download', { ref, peer, group }); }
          else await call(paused ? 'resume' : 'pause', { id });
        } catch (e) { toast(e.message, 'err'); } finally { busy = false; action.disabled = false; }
      };
      remove.onclick = async () => { if (!confirm('Remove this local transfer and stop sharing it? Saved copies are kept.')) return;
        try { await call('remove', { id }); closeSheet(); } catch (e) { toast(e.message, 'err'); } };
      tick();
    }
    async function send(file, peer, group) {
      if (file.size > MAX) { toast('Choose a file up to 512 MB', 'err'); return; }
      const id = Array.from(crypto.getRandomValues(new Uint8Array(16)), n => n.toString(16).padStart(2, '0')).join('');
      let cancel = false;
      const panel = sheet('<div class="h">Sending ' + esc(file.name) + '</div><p class="sub file-status">Uploading to your account…</p>'
        + '<progress max="100" value="0" style="width:100%"></progress><div class="actions"><button class="btn ghost cancel">Cancel upload</button><button class="btn ghost hide">Hide</button></div>');
      panel.querySelector('.cancel').onclick = () => { cancel = true; };
      panel.querySelector('.hide').onclick = closeSheet;
      try {
        await call('begin', { id, name: file.name, mime: file.type || 'application/octet-stream', size: String(file.size), peer, group });
        for (let offset = 0; offset < file.size; offset += BLOCK) {
          if (cancel) throw Error('Upload cancelled');
          const bytes = new Uint8Array(await file.slice(offset, offset + BLOCK).arrayBuffer());
          let binary = ''; for (const b of bytes) binary += String.fromCharCode(b);
          await call('append', { id, offset: String(offset), data: btoa(binary) });
          panel.querySelector('progress').value = 100 * Math.min(file.size, offset + BLOCK) / Math.max(1, file.size);
        }
        if (cancel) throw Error('Upload cancelled');
        await call('finish', { id }); if (panel.isConnected) { closeSheet(); show(id, null, peer, group); } else toast('File is being prepared');
      } catch (e) { try { await call('cancelUpload', { id }); } catch (_) {} if (panel.isConnected) closeSheet(); toast(e.message, 'err'); }
    }
    async function list() {
      try { const transfers = JSON.parse((await call('list')).transfers);
        const panel = sheet('<div class="h">File transfers</div><div class="file-list"></div>');
          if (!transfers.length) panel.querySelector('.file-list').textContent = 'No local file transfers';
        transfers.forEach(f => { const b = document.createElement('button'); b.className = 'btn ghost'; b.textContent = f.name + ' · ' + f.status;
          b.onclick = () => { closeSheet(); show(f.id); }; panel.querySelector('.file-list').appendChild(b); });
      } catch (e) { toast(e.message, 'err'); }
    }
    return { show, send, list };
  }
  root.ParlonsFiles = { parse, size, create };
  if (typeof module !== 'undefined') module.exports = { parse, size };
})(typeof window !== 'undefined' ? window : globalThis);
