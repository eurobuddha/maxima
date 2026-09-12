const {test} = require('node:test');
const assert = require('node:assert/strict');
const {ParlonsCalls} = require('../../main/resources/panel/calls.js');
function deferred() { let resolve; const promise = new Promise(r => resolve = r); return {promise, resolve}; }
function fixture() {
  const sent = [], states = [], timers = new Map(), peers = [], media = [];
  let serial = 0;
  class PC {
    constructor() { this.ice = []; this.tracks = []; peers.push(this); }
    addTrack(t) { this.tracks.push(t); }
    async createOffer() { return {type:'offer', sdp:'offer-sdp'}; }
    async createAnswer() { assert.ok(this.remoteDescription); return {type:'answer', sdp:'answer-sdp'}; }
    async setLocalDescription(sdp) { this.localDescription = sdp; this.onicecandidate({candidate:{sdpMid:'0',sdpMLineIndex:0,candidate:'candidate-local'}}); }
    async setRemoteDescription(sdp) { if (this.wait) await this.wait.promise; this.remoteDescription = sdp; }
    async addIceCandidate(c) { assert.ok(this.remoteDescription); this.ice.push(c); }
    close() { this.closed = true; }
  }
  const env = {crypto:{randomUUID: () => 'test-id-' + ++serial}, Date:{now:()=>100000},
    setTimeout(fn) { const id = ++serial; timers.set(id,fn); return id; }, clearTimeout(id) { timers.delete(id); },
    MediaStream: class { addTrack() {} }, RTCPeerConnection: PC,
    navigator:{mediaDevices:{async getUserMedia() { const t={enabled:true,stop(){this.stopped=true;}};
      const m={getTracks:()=>[t],getAudioTracks:()=>[t],getVideoTracks:()=>[]};media.push(m);return m;}}}};
  const calls = new ParlonsCalls({env, signal: async b => {sent.push(b);}, changed:c=>states.push(c.state), streams(){},known:k=>k==='peer'});
  calls.ready=true;
  const offer = (id='remote-call') => ({kind:'offer',from:'peer',name:'Peer',ref:id,payload:'offer-sdp',time:99999});
  const signal = (kind,payload='') => calls.receive({kind,from:'peer',ref:calls.current.id,payload});
  return {calls,env,sent,states,timers,peers,media,offer,signal};
}
const idle = () => new Promise(r=>setImmediate(r));
test('outgoing offer precedes ICE, answer connects, hangup preserves id and releases media',async()=>{
  const f=fixture();await f.calls.start('peer','Peer',false);await idle();
  assert.deepEqual(f.sent.map(s=>s.kind),['offer','ice']);const id=f.calls.current.id;
  await f.signal('answer','answer-sdp');f.peers[0].connectionState='connected';f.peers[0].onconnectionstatechange();
  assert.equal(f.calls.current.state,'LIVE');assert.equal(f.timers.size,0);
  f.calls.hangup(id);await idle();assert.equal(f.sent.at(-1).id,id);assert.equal(f.sent.at(-1).kind,'bye');
  assert.equal(f.peers[0].closed,true);assert.equal(f.media[0].getTracks()[0].stopped,true);
});
test('early and concurrent ICE wait until remote SDP succeeds',async()=>{
  const f=fixture();await f.calls.start('peer','Peer',false);
  await f.signal('ice','0\n0\ncandidate-early');const wait=deferred();f.peers[0].wait=wait;
  const answering=f.signal('answer','answer-sdp');await idle();await f.signal('ice','0\n0\ncandidate-during');
  assert.equal(f.peers[0].ice.length,0);wait.resolve();await answering;
  assert.equal(f.peers[0].ice.length,2);
});
test('duplicate answers do not repeat setRemoteDescription',async()=>{
  const f=fixture();await f.calls.start('peer','Peer',false);await f.signal('answer','first');await f.signal('answer','duplicate');
  assert.equal(f.peers[0].remoteDescription.sdp,'first');
});
test('late microphone permission after hangup releases tracks and cannot replace another call',async()=>{
  const f=fixture(), wait=deferred();const get=f.env.navigator.mediaDevices.getUserMedia;
  f.env.navigator.mediaDevices.getUserMedia=()=>wait.promise;
  await f.calls.receive(f.offer());const accepting=f.calls.accept(f.calls.current.id);f.calls.hangup(f.calls.current.id);
  await f.calls.receive(f.offer('next'));const m=await get();wait.resolve(m);await accepting;
  assert.equal(f.calls.current.id,'next');assert.equal(m.getTracks()[0].stopped,true);assert.equal(f.peers.length,0);
});
test('incoming call waits for Answer before capturing any media',async()=>{
  const f=fixture();await f.calls.receive(f.offer());assert.equal(f.media.length,0);
  await f.calls.accept('old');assert.equal(f.media.length,0);await f.calls.accept('remote-call');await idle();
  assert.equal(f.media.length,1);assert.deepEqual(f.sent.map(s=>s.kind),['answer','ice']);
});
test('sibling answer cancels ringing and an in-progress answer; own taken echo is harmless',async()=>{
  const f=fixture();await f.calls.receive(f.offer());
  await f.calls.receive({kind:'taken',ref:'remote-call',exceptClient:f.calls.client});assert.ok(f.calls.current);
  await f.calls.receive({kind:'taken',ref:'remote-call'});assert.equal(f.calls.current,null);assert.equal(f.sent.length,0);
  await f.calls.receive(f.offer('next'));f.calls.current.state='CONNECTING';
  await f.calls.receive({kind:'taken',ref:'next',exceptClient:'sibling'});assert.equal(f.calls.current,null);
});
test('stale, duplicate, unknown and busy offers cannot replace a current call',async()=>{
  const f=fixture();await f.calls.receive({...f.offer(),time:1});await f.calls.receive({...f.offer(),from:'stranger'});assert.equal(f.calls.current,null);
  await f.calls.receive(f.offer());await f.calls.receive(f.offer());assert.equal(f.states.length,1);
  await f.calls.receive(f.offer('second'));await idle();assert.equal(f.sent.at(-1).kind,'busy');assert.equal(f.calls.current.id,'remote-call');
});
test('ring timeout and permission denial end the exact call',async()=>{
  const f=fixture();await f.calls.receive(f.offer());[...f.timers.values()][0]();await idle();
  assert.equal(f.calls.current,null);assert.equal(f.sent.at(-1).id,'remote-call');
  f.env.navigator.mediaDevices.getUserMedia=async()=>{throw Object.assign(new Error('denied'),{name:'NotAllowedError'});};
  await f.calls.start('peer','Peer',false);assert.equal(f.calls.current,null);assert.equal(f.timers.size,0);
});
