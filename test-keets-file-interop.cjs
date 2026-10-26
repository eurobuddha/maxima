// Reuses keets/test/media-recovery-p2p.js: real private DHT, separate stores/keys,
// synthetic data only. No wallet, existing account, public bootstrap or mailbox.
const fs = require('fs'), path = require('path'), os = require('os'), crypto = require('crypto')
const { spawn } = require('child_process')
const keets = path.resolve(process.argv[2] || '../keets')
const DHT = require(path.join(keets, 'sidecar/node_modules/hyperdht'))
const createMedia = require(path.join(keets, 'sidecar/media'))
const startApi = require(path.join(keets, 'sidecar/api'))
;(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'keets-file-interop-'))
  const token = crypto.randomBytes(32).toString('hex')
  const boot = DHT.bootstrapper(45000 + Math.floor(Math.random() * 10000), '127.0.0.1'), peers = [], servers = []
  let child
  const timer = setTimeout(() => { if (child) child.kill(); console.error('Interoperability probe timed out') }, 240000)
  try {
    await boot.ready()
    const ports = []
    for (const name of ['parlons', 'keetchat']) {
      const keetsdir = path.join(dir, name); fs.mkdirSync(keetsdir)
      const media = createMedia({ keetsdir, seed: crypto.randomBytes(32), bootstrap: [{ host: '127.0.0.1', port: boot.address().port }], maxconns: 4 })
      peers.push(media); await media.start()
      const api = startApi({ port: 0, token, mypub: crypto.randomBytes(32).toString('hex'), media,
        messages: { send () { throw new Error('Fleet/message route is forbidden in file test') } }, version: 'test' })
      servers.push(api)
      await new Promise((resolve, reject) => { api.once('listening', resolve); api.once('error', reject) })
      ports.push(api.address().port)
    }
    const cp = ['core/build/classes/java/main', 'core/build/classes/java/parity'].map(p => path.resolve(p)).join(path.delimiter)
    child = spawn(process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java') : 'java',
      ['-Xmx256m', '-cp', cp, 'com.eurobuddha.maxima.files.KeetsInteropProbe', ...ports.map(String), token], { stdio: 'inherit' })
    const code = await new Promise((resolve, reject) => { child.once('exit', resolve); child.once('error', reject) })
    if (code !== 0) throw new Error('Java interoperability probe failed: ' + code)
  } finally {
    clearTimeout(timer)
    for (const server of servers) await new Promise(resolve => server.close(resolve))
    for (const peer of peers) { await peer.swarm.destroy(); if (peer.store) await peer.store.close() }
    await boot.destroy(); fs.rmSync(dir, { recursive: true, force: true })
  }
})().catch(e => { console.error(e.message); process.exitCode = 1 })
