// Parlons panel (Phase 1 placeholder). Served same-origin by ParlonsLocal; the page's CSP allows no inline script.
(function () {
  'use strict';
  const $ = id => document.getElementById(id);
  const esc = s => String(s == null ? '' : s);

  async function api(method, body) {
    const r = await fetch('/api/' + method, {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {})
    });
    if (r.status === 401) { $('state').textContent = 'signed out - open the panel with a fresh link'; throw new Error('401'); }
    return r.json();
  }
  async function get(name) {
    const r = await fetch('/api/' + name, { credentials: 'same-origin' });
    return r.json();
  }

  async function refresh() {
    const p = await api('ping');
    $('name').textContent = esc(p.name);
    $('address').textContent = esc(p.permanent || p.address || '');
    const st = await api('node.status');
    $('version').textContent = esc(st.version);
    const inv = await get('invite');
    if (inv.invite) {
      $('inviteText').textContent = 'Scan this with the Parlons app on your phone, or paste the text (the code half works once):';
      $('invite').textContent = inv.invite;
      try {
        const qr = qrcode(0, 'M');   // the invite is ~640 bytes: a large but scannable code
        qr.addData(inv.invite);
        qr.make();
        $('inviteQr').innerHTML = qr.createImgTag(3, 0);
      } catch (e) { $('inviteQr').textContent = ''; }
    } else {
      $('inviteText').textContent = 'No pairing code is outstanding.';
      $('invite').textContent = '';
      $('inviteQr').innerHTML = '';
    }
    const d = await api('pair.list');
    const rows = [];
    for (const dev of (d.authorized || [])) {
      rows.push('<tr><td>' + (dev.local ? 'this computer' : 'paired') + '</td><td>'
        + esc(dev.label).replace(/[<>&]/g, c => ({'<':'&lt;','>':'&gt;','&':'&amp;'}[c]))
        + '<br><code>' + esc(dev.key) + '</code></td></tr>');
    }
    for (const k of (d.pending || [])) {
      rows.push('<tr><td>waiting</td><td><code>' + esc(k) + '</code></td></tr>');
    }
    $('devices').innerHTML = rows.join('') || '<tr><td class="muted">none</td></tr>';
    $('state').textContent = 'connected';
  }

  $('newcode').addEventListener('click', async () => {
    await api('pair.newcode');
    setTimeout(refresh, 800);   // the invite file follows within a moment
  });

  function listen() {
    const es = new EventSource('/events');
    es.addEventListener('push', e => {
      const box = $('events');
      if (box.classList.contains('muted')) { box.classList.remove('muted'); box.textContent = ''; }
      box.textContent = new Date().toLocaleTimeString() + '  ' + e.data + '\n' + box.textContent;
      if (e.data.indexOf('"type":"message"') >= 0) { refresh(); }
    });
    es.onerror = () => { $('state').textContent = 'reconnecting…'; };
    es.addEventListener('hello', () => { $('state').textContent = 'connected'; });
  }

  refresh().then(listen).catch(() => {});
})();
