#!/usr/bin/env python3
"""Validate real Cloud Node dialogs in a disposable emulator; never activate a send action."""
import argparse, json, subprocess, time
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--adb', required=True)
p.add_argument('--serial', required=True)
p.add_argument('--package', default='com.eurobuddha.parlons.cloud')
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
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            result = json.loads(adb('shell', 'run-as', a.package, 'cat', 'files/language-node-proof.json'))
            if result['mode'] == mode and (locale is None or result['locale'] == locale):
                return result
        except (subprocess.CalledProcessError, json.JSONDecodeError):
            pass
        time.sleep(.25)
    raise AssertionError('Timed out waiting for ' + mode + '/' + str(locale))

assert a.package == 'com.eurobuddha.parlons.cloud'
adb('shell','pm','grant',a.package,'android.permission.POST_NOTIFICATIONS')
results = []
modes = a.modes or ['overview','error','name','revoke','admin','qr','hosting','hosting-create','hosting-options','hosting-actions','hosting-pause']
component = 'LanguageNodePreviewActivity'
for mode in modes:
    adb('shell', 'am', 'force-stop', a.package)
    adb('shell', 'run-as', a.package, 'rm', '-f', 'files/language-node-proof.json')
    adb('shell', 'am', 'start', '-W', '-n', a.package + '/com.eurobuddha.maxima.app.i18n.' + component, '--es', 'mode', mode)
    assert proof(mode)['same_views_and_drafts']
    for code in ['ru', 'zh-Hans', 'en']:
        adb('shell', 'am', 'broadcast', '-a', a.package + '.LANGUAGE_NODE_FIXTURE', '-p', a.package, '--es', 'code', code)
        result = proof(mode, code)
        assert result['same_views_and_drafts'], result
        keys = {
            'panel-own':['nodePanel.title','nodePanel.publicRelay','nodePanel.verified','nodePanel.builtin','nodePanel.pinHelp','nodePanel.pin','nodePanel.copyPermanent'],
            'panel-direct':['nodePanel.title','nativeNetwork.directlyReachable','node.directAddress','nodePanel.pinHelp','nodePanel.pin'],
            'panel-pinned':['nodePanel.title','nodePanel.pinned','nodePanel.repin','nodePanel.clearPin','node.republish'],
            'error':[],
            'overview':['settings.yourName','nativeNode.alwaysOnline','nativeNode.address','nativeNode.manageHosting','nativeNode.pairOther'],
            'name':['settings.yourName','nativeNode.nameAnnounce','nativeNode.nameExample','common.cancel'],
            'revoke':['nativeNode.revokeTitle','common.revoke','common.cancel'],
            'admin':['nativeNode.inviteDevice','nativeNode.adminHelp','pairing.adminPassword','common.continue'],
            'qr':['nativeNode.address','language.close'],
            'hosting':['nativeNode.hostingOptions','hosting.create','language.close'],
            'hosting-create':['nativeNode.hostFriend','nativeNode.createChatHelp','nativeNode.accountExample','common.create'],
            'hosting-options':['nativeNode.hostingComputer','common.refresh','hosting.stop','language.close'],
            'hosting-actions':['hosting.showInvite','nativeNode.pauseAccount','language.close'],
            'hosting-pause':['nativeNode.pauseHelp','common.pause','common.cancel'],
        }[mode]
        labels = result['labels']
        for key in keys:
            expected = catalog[key][code]
            assert expected in labels or expected.upper() in labels, (key, result)
        if mode.startswith('panel-'):
            for literal in ['relay.example:9501','MAX#FixtureLiteral中文Русский{count}$1','12:34 Node 中文 Русский {count} $1','123456789','Draft 中文 Привет {count} $1']:
                assert literal in labels,(literal,result)
            if mode=='panel-direct':assert '203.0.113.7:9536' in labels,result
        if mode=='error':assert catalog['nativeNode.unreachable'][code].replace('{error}','Node 中文 Русский {count} $1') in labels,result
        if mode=='overview':
            for literal in ['Owner 中文 Русский {count} $1','MAX#FixtureLiteral中文Русский{count}$1','Device 中文 Русский {count} $1']:
                assert literal in labels,(literal,result)
        if mode=='hosting':
            assert 'running — '+catalog['hosting.stateRunning'][code] in labels,result
            assert catalog['nativeNode.hostingCount'][code].replace('{count}','1').replace('{limit}','5') in labels,result
        if mode=='hosting-actions':assert 'running' in labels,result
        if mode=='hosting-pause':assert catalog['nativeNode.pauseTitle'][code].replace('{name}','running') in labels,result
        if mode=='revoke':assert catalog['nativeNode.revokeHelp'][code].replace('{name}','Device 中文 Русский {count} $1') in labels,result
        assert result['input_count']==(1 if mode in ['name','admin','hosting-create'] or mode.startswith('panel-') else 0),result
        (screens / (a.package+'-node-'+mode+'-'+code+a.suffix+'.png')).write_bytes(adb('exec-out','screencap','-p',raw=True))
        if mode.startswith('panel-'):
            for _ in range(5):adb('shell','input','swipe','360','1400','360','350','150')
            (screens / (a.package+'-node-'+mode+'-'+code+a.suffix+'-bottom.png')).write_bytes(adb('exec-out','screencap','-p',raw=True))
            for _ in range(5):adb('shell','input','swipe','360','350','360','1400','150')
        results.append(result)
        print('PASS', a.package, mode, code, 'same activity/dialog/fields, literal drafts and selection', flush=True)
adb('shell', 'am', 'force-stop', a.package)
(root / 'build/localization' / ('android-node' + a.suffix + '.json')).write_text(
    json.dumps(results, ensure_ascii=False, indent=2) + '\n')
