/* Browser adaptation of portal/PortalCallManager: same authenticated signals, fleet STUN,
 * one call, 45s ring / 20s connect, and ICE only after remote SDP succeeds. No media server. */
(function (root) {
  'use strict';
  class ParlonsCalls {
    constructor({ signal, changed, streams, known, env = globalThis }) {
      this.env = env; this.send = signal; this.changed = changed; this.streams = streams;
      this.known = known; this.current = null; this.ended = new Set(); this.ready = false;
      this.client = env.crypto.randomUUID(); this.outbox = Promise.resolve();
    }
    valid(c) { return this.current === c; }
    state(c, state, reason = '') {
      if (!this.valid(c)) return;
      c.state = state; c.reason = reason; this.changed(c);
    }
    timer(c, ms, reason) {
      this.env.clearTimeout(c.timer);
      c.timer = this.env.setTimeout(() => this.end(c, reason, true), ms);
    }
    signal(c, kind, payload = '') {
      const body = { peer: c.peer, id: c.id, kind, payload,
        memo: kind === 'offer' && c.video ? 'video' : '', client: this.client };
      // Like PortalCallManager's send executor: offer/answer precedes its gathered ICE.
      const task = this.outbox.then(() => this.send(body));
      this.outbox = task.catch(() => {});
      return task;
    }
    make(id, peer, name, video, offer) {
      const c = { id, peer, name: name || 'Contact', video, offer, ice: [], localIce: [],
        signalled: false, remoteReady: false, muted: false, cameraOff: false };
      this.current = c; return c;
    }
    async start(peer, name, video) {
      if (this.current) return;
      if (!this.ready) throw new Error('Call connection is reconnecting. Try again shortly.');
      if (!this.known(peer)) throw new Error('Choose a contact to call.');
      const c = this.make(this.env.crypto.randomUUID(), peer, name, video);
      this.state(c, 'OUTGOING_RINGING'); this.timer(c, 45000, 'No answer');
      try {
        await this.createPeer(c); if (!this.valid(c)) return;
        const offer = await c.pc.createOffer(); if (!this.valid(c)) return;
        await c.pc.setLocalDescription(offer); if (!this.valid(c)) return;
        await this.signal(c, 'offer', offer.sdp); this.flushLocal(c);
      } catch (e) { this.failed(c, e); }
    }
    async receive(ev) {
      let c = this.current;
      if (ev.kind === 'taken') {
        if (c && ev.ref === c.id && ev.exceptClient !== this.client && c.offer) this.end(c, 'Answered on another device', false);
        return;
      }
      if (ev.kind === 'offer') {
        if (!ev.ref || !ev.from || !this.known(ev.from) || (ev.time && this.env.Date.now() - Number(ev.time) > 90000)
            || this.ended.has(ev.ref) || (c && c.id === ev.ref)) return;
        if (c) { this.signal({peer: ev.from, id: ev.ref}, 'busy').catch(() => {}); return; }
        c = this.make(ev.ref, ev.from, ev.name, ev.memo === 'video', ev.payload);
        this.state(c, 'INCOMING_RINGING'); this.timer(c, 45000, 'Missed call'); return;
      }
      if (!c || ev.ref !== c.id || String(ev.from).toLowerCase() !== c.peer.toLowerCase()) return;
      try {
        if (ev.kind === 'ice') {
          const p = String(ev.payload).split('\n');
          if (p.length < 3 || !/^\d+$/.test(p[1])) return;
          const ice = {sdpMid: p[0] === 'null' ? null : p[0], sdpMLineIndex: Number(p[1]), candidate: p.slice(2).join('\n')};
          if (c.remoteReady) await c.pc.addIceCandidate(ice);
          else if (c.ice.length < 256) c.ice.push(ice);
        } else if (ev.kind === 'answer' && c.state === 'OUTGOING_RINGING' && c.pc) {
          this.state(c, 'CONNECTING'); this.timer(c, 20000, 'Could not connect');
          await this.remote(c, 'answer', ev.payload);
        } else if (ev.kind === 'bye' || ev.kind === 'busy') this.end(c, ev.kind === 'busy' ? 'Busy' : 'Call ended', false);
      } catch (e) { this.failed(c, e); }
    }
    async accept(id) {
      const c = this.current;
      if (!c || c.id !== id || c.state !== 'INCOMING_RINGING') return;
      this.state(c, 'CONNECTING'); this.timer(c, 20000, 'Could not connect');
      try {
        await this.createPeer(c); if (!this.valid(c)) return;
        await this.remote(c, 'offer', c.offer); if (!this.valid(c)) return;
        const answer = await c.pc.createAnswer(); if (!this.valid(c)) return;
        await c.pc.setLocalDescription(answer); if (!this.valid(c)) return;
        await this.signal(c, 'answer', answer.sdp); this.flushLocal(c);
      } catch (e) { this.failed(c, e); }
    }
    async createPeer(c) {
      const media = await this.env.navigator.mediaDevices.getUserMedia({ audio: true,
        video: c.video ? {width: {ideal: 960}, height: {ideal: 540}, frameRate: {ideal: 24}} : false });
      if (!this.valid(c)) { media.getTracks().forEach(t => t.stop()); return; }
      c.media = media;
      c.pc = new this.env.RTCPeerConnection({iceServers: [
        {urls: 'stun:95.179.179.181:9501'}, {urls: 'stun:65.109.31.226:9501'},
        {urls: 'stun:45.77.246.226:9501'}, {urls: 'stun:78.141.237.9:9501'}
      ]});
      c.remoteMedia = new this.env.MediaStream();
      c.pc.ontrack = ev => {
        if (!this.valid(c)) return;
        c.remoteMedia.addTrack(ev.track); this.streams(c.media, c.remoteMedia);
      };
      c.pc.onicecandidate = ev => {
        if (!this.valid(c) || !ev.candidate) return;
        const ice = ev.candidate;
        const payload = ice.sdpMid + '\n' + ice.sdpMLineIndex + '\n' + ice.candidate;
        if (c.signalled) this.signal(c, 'ice', payload).catch(() => {});
        else c.localIce.push(payload);
      };
      c.pc.onconnectionstatechange = () => {
        if (!this.valid(c)) return;
        if (c.pc.connectionState === 'connected') {
          this.env.clearTimeout(c.timer); c.liveSince = this.env.Date.now(); this.state(c, 'LIVE');
        } else if (['failed', 'disconnected'].includes(c.pc.connectionState)) this.end(c, 'Connection lost', true);
      };
      media.getTracks().forEach(t => c.pc.addTrack(t, media)); this.streams(media, c.remoteMedia);
    }
    async remote(c, type, sdp) {
      await c.pc.setRemoteDescription({type, sdp});
      if (!this.valid(c)) return;
      // Keep queuing while setRemoteDescription is pending, as in PortalCallManager.setRemote.
      while (this.valid(c) && c.ice.length) await c.pc.addIceCandidate(c.ice.shift());
      if (this.valid(c)) c.remoteReady = true;
    }
    flushLocal(c) {
      if (!this.valid(c)) return;
      c.signalled = true;
      c.localIce.splice(0).forEach(p => this.signal(c, 'ice', p).catch(() => {}));
    }
    mute() {
      const c = this.current; if (!c || !c.media) return;
      c.muted = !c.muted; c.media.getAudioTracks().forEach(t => { t.enabled = !c.muted; }); this.changed(c);
    }
    camera() {
      const c = this.current; if (!c || !c.media) return;
      c.cameraOff = !c.cameraOff; c.media.getVideoTracks().forEach(t => { t.enabled = !c.cameraOff; }); this.changed(c);
    }
    hangup(id) { const c = this.current; if (c && c.id === id) this.end(c, 'Call ended', true); }
    failed(c, e) {
      this.end(c, e && (e.name === 'NotAllowedError' || e.name === 'NotFoundError')
        ? 'Microphone or camera unavailable. Check device permissions in system settings.' : (e.message || 'Could not connect'),
        !(e && /another device/.test(e.message)));
    }
    end(c, reason, bye) {
      if (!this.valid(c)) return;
      if (bye) this.signal(c, 'bye').catch(() => {}); // capture the id before clearing it
      this.env.clearTimeout(c.timer); this.ended.add(c.id);
      if (this.ended.size > 100) this.ended.delete(this.ended.values().next().value);
      this.current = null;
      if (c.pc) c.pc.close();
      if (c.media) c.media.getTracks().forEach(t => t.stop());
      this.streams(null, null); c.state = 'ENDED'; c.reason = reason; this.changed(c);
    }
  }
  root.ParlonsCalls = ParlonsCalls;
})(typeof module === 'object' ? module.exports : window);
