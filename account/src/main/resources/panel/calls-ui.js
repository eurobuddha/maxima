/* Full-window incoming screen, using the panel's existing theme, avatar and button styles. */
window.initParlonsCalls = function ({api, avatar, toast}) {
  const $ = id => document.getElementById(id);
  const screen = document.createElement('section');
  screen.id = 'callScreen'; screen.className = 'call-screen'; screen.hidden = true;
  screen.setAttribute('role', 'dialog'); screen.setAttribute('aria-modal', 'true');
  screen.setAttribute('aria-labelledby', 'callName');
  screen.innerHTML = '<div class="call-heading"><div class="call-brand">Parlons</div><div id="callAvatar"></div><h1 id="callName"></h1><p id="callStatus" role="status"></p></div>'
    + '<div class="call-video" id="callVideo" hidden><video id="callRemote" autoplay playsinline></video><video id="callLocal" autoplay playsinline muted></video></div>'
    + '<p class="call-audio-note" id="callAudioNote" hidden>Sound is paused by your browser. <button class="btn" id="callEnableSound">Enable sound</button></p>'
    + '<div class="call-controls"><button class="btn" id="callMute" hidden>Mute</button><button class="btn" id="callCamera" hidden>Camera off</button>'
    + '<button class="btn call-decline" id="callEnd">Decline</button><button class="btn call-answer" id="callAnswer">Answer</button></div>';
  document.body.appendChild(screen);
  let ringing = null, audio = null, previousFocus = null, shownId = '', ticker = null;
  function stopRing() {
    clearInterval(ringing); ringing = null;
    if (audio) { audio.close().catch(() => {}); audio = null; }
    $('callAudioNote').hidden = true;
  }
  function ring() {
    stopRing();
    try {
      audio = new AudioContext(); const context = audio;
      const pulse = () => {
        if (context.state !== 'running') { $('callAudioNote').hidden = false; return; }
        const now = context.currentTime;
        for (const delay of [0, 0.65]) {
          const gain = context.createGain(); gain.connect(context.destination);
          gain.gain.setValueAtTime(0, now + delay); gain.gain.linearRampToValueAtTime(0.12, now + delay + 0.02);
          gain.gain.setValueAtTime(0.12, now + delay + 0.4); gain.gain.linearRampToValueAtTime(0, now + delay + 0.45);
          for (const hz of [440, 480]) { const tone = context.createOscillator(); tone.frequency.value = hz;
            tone.connect(gain); tone.start(now + delay); tone.stop(now + delay + 0.46); }
        }
      };
      context.resume().then(pulse).catch(() => { $('callAudioNote').hidden = false; });
      ringing = setInterval(pulse, 2600);
    } catch (e) { $('callAudioNote').hidden = false; }
  }
  const calls = new window.ParlonsCalls({
    signal: body => api('call.signal', body),
    // ParlonsControl authenticates every offer and refuses unknown contacts before this SSE feed.
    known: peer => typeof peer === 'string' && peer.length > 0,
    streams(local, remote) {
      $('callLocal').srcObject = local; $('callRemote').srcObject = remote;
      if (remote) $('callRemote').play().catch(() => { $('callAudioNote').hidden = false; });
    },
    changed(c) {
      document.title = 'Parlons — ' + c.state; // Electron observes only fixed states from this trusted origin.
      clearInterval(ticker);
      if (c.state === 'ENDED') {
        stopRing(); screen.hidden = true; $('app').inert = false; shownId = '';
        if (previousFocus && previousFocus.isConnected) previousFocus.focus();
        toast(c.reason); return;
      }
      const incoming = c.state === 'INCOMING_RINGING';
      if (shownId !== c.id) {
        previousFocus = document.activeElement; shownId = c.id;
        screen.hidden = false; $('app').inert = true;
        $('callAvatar').innerHTML = avatar(c.peer, c.name, 'l');
        $('callName').textContent = c.name;
        if (incoming) ring();
      }
      if (!incoming) stopRing();
      $('callAnswer').hidden = !incoming;
      $('callEnd').textContent = incoming ? 'Decline' : 'End call';
      $('callMute').hidden = !c.media; $('callMute').textContent = c.muted ? 'Unmute' : 'Mute';
      $('callMute').setAttribute('aria-pressed', String(c.muted));
      $('callCamera').hidden = !c.video || !c.media; $('callCamera').textContent = c.cameraOff ? 'Camera on' : 'Camera off';
      $('callCamera').setAttribute('aria-pressed', String(c.cameraOff));
      // Keep the remote element playing for voice calls too (only its visual container is hidden).
      $('callVideo').hidden = !c.video || incoming;
      const status = () => {
        const elapsed = c.liveSince ? Math.floor((Date.now() - c.liveSince) / 1000) : 0;
        $('callStatus').textContent = incoming ? 'Incoming ' + (c.video ? 'video' : 'voice') + ' call'
          : c.state === 'OUTGOING_RINGING' ? 'Calling…' : c.state === 'CONNECTING' ? 'Connecting…'
          : (c.video ? 'Video call' : 'Voice call') + ' · ' + Math.floor(elapsed / 60) + ':' + String(elapsed % 60).padStart(2, '0');
      };
      status(); if (c.state === 'LIVE') ticker = setInterval(status, 1000);
      $('callAnswer').onclick = () => calls.accept(c.id);
      $('callEnd').onclick = () => calls.hangup(c.id);
      if (incoming) $('callAnswer').focus();
    }
  });
  $('callMute').onclick = () => calls.mute(); $('callCamera').onclick = () => calls.camera();
  $('callEnableSound').onclick = () => {
    if (calls.current && calls.current.state === 'INCOMING_RINGING') ring();
    $('callRemote').play().then(() => { $('callAudioNote').hidden = true; }).catch(() => {});
  };
  screen.addEventListener('keydown', e => {
    if (e.key !== 'Tab') return;
    const buttons = Array.from(screen.querySelectorAll('button')).filter(b => !b.hidden && b.offsetParent);
    if (!buttons.length) return;
    if (e.shiftKey && document.activeElement === buttons[0]) { e.preventDefault(); buttons[buttons.length - 1].focus(); }
    else if (!e.shiftKey && document.activeElement === buttons[buttons.length - 1]) { e.preventDefault(); buttons[0].focus(); }
  });
  window.addEventListener('pagehide', () => { if (calls.current) calls.hangup(calls.current.id); stopRing(); });
  return calls;
};
