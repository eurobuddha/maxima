# Privacy

Effective 6 September 2026. Applies to every Parlons app and program: the Android app, Parlons Cloud for Android and iPhone, Parlons Desktop, the Parlons account programs and the relays.

## What the authors collect

Nothing. There is no analytics, no crash reporting service, no advertising identifier, no tracking, no third-party SDK, and no account with the authors to sign up for. The App Store privacy label is "Data Not Collected". Apple's push service sees an iPhone's device token, as it does for every app that sends notifications.

## What stays on your device

The Android app and the desktop hold your identity (24 words), your contacts and your chats on the device, readable only by you. Parlons Cloud on a phone holds only a device key and a cache of what you looked at; the account holds the rest.

## What a Parlons account holds

An account keeps your identity, contacts, chat history and paired devices on the machine it runs on. If that machine is yours, that is where your data is. If someone hosts the account for you, that person operates the machine and has the access an operator has; seeds are encrypted at rest under their passphrase. You can leave at any time with an encrypted backup and keep your address.

## What travels

Every message is sealed end to end on the sending device with the recipient's key and signed by the sender, then carried by relays. A relay sees a sealed blob and a routing key; it cannot open it, alter it, or learn more than that a key is talking to it. A phone or computer talking to its own account does so the same way.

## The iPhone wake path

iOS cannot keep a background connection, so when the app is closed your account sends a content-free wake to a small relay run by the app's publisher, which forwards it to Apple's push service. The relay receives your Apple push token and the word "message" or "call": no sender, no recipient, no text. It stores nothing. Turn the switch off in Settings and the app catches up on its own schedule instead.

## Children

The apps have no content of their own and no sign-up; chat with strangers is possible, so the App Store rating is 12+.

## Changes and contact

This page is versioned with the [source]({{REPO}}). Questions: the [issue tracker]({{ISSUES}}).
