#!/usr/bin/env python3
"""Validate real chat dialogs in a disposable emulator; never activate a send action."""
import argparse, json, subprocess, time
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--adb', required=True)
p.add_argument('--serial', required=True)
p.add_argument('--package', required=True)
p.add_argument('--modes', nargs='+')
p.add_argument('--suffix',default='')
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
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        try:
            result = json.loads(adb('shell', 'run-as', a.package, 'cat', 'files/language-chat-proof.json'))
            if result['mode'] == mode and (locale is None or result['locale'] == locale):
                return result
        except (subprocess.CalledProcessError, json.JSONDecodeError):
            pass
        time.sleep(.25)
    raise AssertionError('Timed out waiting for ' + mode + '/' + str(locale))

results = []
modes = ['caption', 'menu', 'info']
if a.package != 'com.eurobuddha.maxima.app':
    modes.append('payment')
else:
    modes.append('confirm')
modes = a.modes or modes
for mode in modes:
    adb('shell', 'am', 'force-stop', a.package)
    adb('shell', 'run-as', a.package, 'rm', '-f', 'files/language-chat-proof.json')
    adb('shell', 'am', 'start', '-W', '-n', a.package + '/com.eurobuddha.maxima.app.i18n.LanguageChatPreviewActivity', '--es', 'mode', mode)
    assert proof(mode)['same_views_and_drafts']
    for code in ['ru', 'zh-Hans', 'en']:
        adb('shell', 'am', 'broadcast', '-a', a.package + '.LANGUAGE_CHAT_FIXTURE', '-p', a.package, '--es', 'code', code)
        result = proof(mode, code)
        assert result['same_views_and_drafts'], result
        keys = {
            'caption': ['androidChat.sendPhoto', 'androidChat.caption', 'chat.send', 'common.cancel'],
            'menu': ['androidChat.photoLibrary', 'files.private', 'files.title', 'desktopChat.voiceNote'],
            'payment': ['androidChat.amount', 'androidChat.memo', 'androidChat.cloudWallet', 'common.cancel'],
            'info': ['language.close'],
            'link': ['link.open','link.copy','link.share'],
            'confirm': ['androidChat.confirm', 'common.cancel'],
        }[mode]
        for key in keys:
            assert catalog[key][code] in result['labels'], (key, result)
        if mode == 'link':assert 'https://example.invalid/中文/Русский?count=$1' in result['labels'],result
        if mode == 'info':
            body = '\n'.join(result['labels'])
            assert catalog['notification.you'][code] in body, result
            assert catalog['androidChat.read'][code] in body, result
            assert '0x' + 'abcd1234' * 8 in body, result
            if a.package != 'com.eurobuddha.maxima.app':
                plural = catalog['androidChat.deliveredMembers'][code]['few' if code == 'ru' else 'other']
                assert plural.replace('{count}', '22') in body, result
        if mode == 'payment':
            assert catalog['androidChat.payCloud'][code].replace('{name}', 'Chats 中文 {count} $1') in result['labels'], result
        if mode == 'confirm':
            assert catalog['androidChat.sendAmount'][code].replace('{amount}', '1.23456789') in result['labels'], result
            assert catalog['androidChat.confirmPayment'][code].replace('{name}', 'Chats 中文 {count} $1') in result['labels'], result
        assert result['input_count'] == {'caption': 1, 'menu': 0, 'payment': 2, 'info': 0, 'confirm': 0, 'link': 0}[mode], result
        (screens / (a.package + '-chat-' + mode + '-' + code + a.suffix + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', raw=True))
        results.append(result)
        print('PASS', a.package, mode, code, 'same activity/dialog/fields, literal drafts and selection', flush=True)
adb('shell', 'am', 'force-stop', a.package)
(root / 'build/localization' / ('android-chat-' + a.package + a.suffix + '.json')).write_text(
    json.dumps(results, ensure_ascii=False, indent=2) + '\n')
