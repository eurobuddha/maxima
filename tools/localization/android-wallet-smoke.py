#!/usr/bin/env python3
"""Validate real Android wallet dialogs in a disposable emulator; never activate a send action."""
import argparse, json, subprocess, time
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--adb', required=True)
p.add_argument('--serial', required=True)
p.add_argument('--package', default='com.eurobuddha.parlons.cloud')
p.add_argument('--modes', nargs='+')
a = p.parse_args()
if a.serial != 'emulator-5582':
    raise SystemExit('Requires the dedicated ParlonsLanguageFixture emulator on port 5582.')
root = Path(__file__).resolve().parents[2]
catalog = json.loads((root / 'localization/catalog.json').read_text())
screens = root / 'build/localization/screenshots'
screens.mkdir(parents=True, exist_ok=True)

def adb(*args, raw=False):
    result = subprocess.run([a.adb, '-s', a.serial, *args], check=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45)
    return result.stdout if raw else result.stdout.decode()

def proof(mode, locale=None):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            result = json.loads(adb('shell', 'run-as', a.package, 'cat', 'files/language-wallet-proof.json'))
            if result['mode'] == mode and (locale is None or result['locale'] == locale):
                return result
        except (subprocess.CalledProcessError, json.JSONDecodeError):
            pass
        time.sleep(.25)
    raise AssertionError('Timed out waiting for ' + mode + '/' + str(locale))

assert a.package in ['com.eurobuddha.parlons.cloud', 'com.eurobuddha.maxima.app']
portal = a.package == 'com.eurobuddha.parlons.cloud'
adb('shell','pm','grant',a.package,'android.permission.POST_NOTIFICATIONS')
results = []
modes = a.modes or (['overview', 'watch', 'send', 'confirm', 'resync', 'uses', 'raise', 'sweep'] if portal else ['overview','send','node','settings','import','uses','help','history'])
component = 'LanguageWalletPreviewActivity' if portal else 'LanguageStandaloneWalletPreviewActivity'
for mode in modes:
    adb('shell', 'am', 'force-stop', a.package)
    adb('shell', 'run-as', a.package, 'rm', '-f', 'files/language-wallet-proof.json')
    adb('shell', 'am', 'start', '-W', '-n', a.package + '/com.eurobuddha.maxima.app.i18n.' + component, '--es', 'mode', mode)
    assert proof(mode)['same_views_and_drafts']
    for code in ['ru', 'zh-Hans', 'en']:
        adb('shell', 'am', 'broadcast', '-a', a.package + '.LANGUAGE_WALLET_FIXTURE', '-p', a.package, '--es', 'code', code)
        result = proof(mode, code)
        assert result['same_views_and_drafts'], result
        keys = {
            'overview': ['cloudWallet.wholeBalance', 'cloudWallet.signingKey', 'cloudWallet.setUsesAction'],
            'watch': ['cloudWallet.watchOnly', 'cloudWallet.watchHint', 'cloudWallet.setWatch'],
            'send': ['wallet.sendMinima', 'cloudWallet.addressHint', 'androidChat.amount', 'cloudWallet.signHelp', 'common.continue'],
            'confirm': ['desktopChat.signSend', 'common.cancel'],
            'resync': ['cloudWallet.resyncTitle', 'cloudWallet.resyncWarning', 'cloudWallet.phraseHint'],
            'uses': ['cloudWallet.setUses', 'common.continue'],
            'raise': ['cloudWallet.raise', 'common.cancel'],
            'sweep': ['cloudWallet.sweep', 'common.cancel'],
        }[mode] if portal else {
            'overview': ['cloudWallet.totalBalance','wallet.used','androidWallet.settings'],
            'send': ['wallet.sendMinima','cloudWallet.addressHint','wallet.amount','desktopChat.signSend'],
            'node': ['androidWallet.node','androidWallet.accessToken','androidWallet.saveNode','androidWallet.resetNode'],
            'settings': ['androidWallet.settings','androidWallet.identity','androidWallet.importPhrase','androidWallet.useIdentity'],
            'import': ['androidWallet.importTitle','androidWallet.importWarning','androidWallet.phraseHint','androidWallet.import'],
            'uses': ['androidWallet.setUses','nativeSettings.set'],
            'help': ['androidWallet.why','androidWallet.usesHelp','help.gotIt'],
            'history': ['wallet.direction','wallet.sent','wallet.amount','wallet.transactionId'],
        }[mode]
        labels = result['labels']
        for key in keys:
            expected = catalog[key][code]
            assert expected in labels or expected.upper() in labels, (key, result)
        full_address = '0x' + 'abcd1234' * 8
        if mode == 'confirm':
            assert catalog['androidChat.sendAmount'][code].replace('{amount}', '1.234567890123456789') in labels, result
            assert catalog['cloudWallet.confirmSend'][code].replace('{address}', full_address) in labels, result
        if mode == 'sweep':
            assert catalog['cloudWallet.sweepTitle'][code].replace('{amount}', '1.234567890123456789') in labels, result
            assert catalog['cloudWallet.sweepWarning'][code].replace('{address}', full_address) in labels, result
        if mode == 'uses' and portal:
            assert catalog['cloudWallet.usesWarning'][code].replace('{used}', '37') in labels, result
        if mode == 'raise':
            assert catalog['cloudWallet.raiseWarning'][code].replace('{used}', '10038') in labels, result
        if mode == 'uses' and not portal:
            assert catalog['androidWallet.usesWarning'][code].replace('{used}', '37') in labels, result
        if mode == 'history':
            assert full_address in labels and 'Wallet 中文 Русский' in labels and 'Send 中文' in labels, result
            assert '−1.234567890123456789 Send 中文' in labels, result
        counts = {'overview':0,'watch':1,'send':2,'confirm':0,'resync':1,'uses':1,'raise':0,'sweep':0,'node':2,'settings':0,'import':1,'help':0,'history':0}
        assert result['input_count'] == counts[mode], result
        (screens / (a.package + '-wallet-' + mode + '-' + code + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', raw=True))
        results.append(result)
        print('PASS', a.package, mode, code, 'same activity/dialog/fields, literal drafts and selection', flush=True)
adb('shell', 'am', 'force-stop', a.package)
(root / 'build/localization' / ('android-wallet-' + a.package + '.json')).write_text(
    json.dumps(results, ensure_ascii=False, indent=2) + '\n')
