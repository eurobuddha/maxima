# Your seed, your keys, what Parlons protects

## The 24 words

Every Parlons identity is 24 words. Your address is derived from them, so the same words anywhere give the same address and the same contacts can reach you. They are also a Minima wallet: anyone who reads them can spend whatever is held against them.

- Write them down on paper when they are shown. Keep the paper offline. Never photograph or email them.
- One identity must run in one place at a time. Restoring the words on a second device while the first still runs confuses your contacts and can reuse wallet keys that must never be reused. Move, do not copy: stop the old device first, or use an encrypted backup bundle, which also carries the wallet's key counters.
- On an account you run, the words are in `seed.txt` inside the data folder, readable only by you. On a hosted account they are encrypted at rest on the host's machine.

## Addresses

Your address is a public key, `Mx…`, and where to find you right now, `@host:port`. The key never changes; the location does, whenever the relay carrying you changes, and your contacts learn the new one automatically. An address is a cache entry, not an identity. If you pin a location service you get a permanent form, `MAX#…`, that never changes; share that when you can.

## What is sealed, and from whom

Every message is encrypted on the sender's device with the recipient's key and signed by the sender. Relays see a sealed blob and a routing key: they can tell that someone is sending to a key, never what, and never open it. Group messages are sealed to each member.

## What Parlons does not protect against

- **Someone holding your device.** Chats stored on an unlocked phone or computer are readable there. Use the device's lock, and the app lock where it exists.
- **Someone holding your 24 words.** They are you.
- **A host you chose reading what its machine stores.** A hosted account keeps its history on that machine. Choose the host accordingly, or run your own.
- **Traffic analysis by a relay you use.** A relay knows which routing keys talk to it, not what they say. Using several relays, which the apps do, limits what any one sees.
- **A contact you added.** Adding someone is a handshake; only add people you mean to.

## Losing things

- **Lost phone, Android app:** restore the words on the new phone; if the old phone held funds, move them first.
- **Lost phone, paired to an account:** revoke that phone from any other paired device or with `parlons revoke`. The account and its history are untouched.
- **Lost the machine running an account:** restore from the words (identity only) or from an encrypted backup bundle (everything). Devices reconnect to the same address.
- **Lost the words and every device:** the identity is gone. There is no reset, because there is nobody who could do it.
