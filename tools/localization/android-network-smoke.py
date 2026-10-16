#!/usr/bin/env python3
"""Validate real Android Network sheets in a disposable emulator; never activate a send action."""
import argparse, json, subprocess, time
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--adb', required=True)
p.add_argument('--serial', required=True)
p.add_argument('--package', default='com.eurobuddha.maxima.app')
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
            result = json.loads(adb('shell', 'run-as', a.package, 'cat', 'files/language-network-proof.json'))
            if result['mode'] == mode and (locale is None or result['locale'] == locale):
                return result
        except (subprocess.CalledProcessError, json.JSONDecodeError):
            pass
        time.sleep(.25)
    raise AssertionError('Timed out waiting for ' + mode + '/' + str(locale))

assert a.package == 'com.eurobuddha.maxima.app'
adb('shell','pm','grant',a.package,'android.permission.POST_NOTIFICATIONS')
results = []
modes = a.modes or ['overview','manage','pin','manual','connect','qr','counters','declined','diagnostics']
component = 'LanguageNetworkPreviewActivity'
for mode in modes:
    adb('shell', 'am', 'force-stop', a.package)
    adb('shell', 'run-as', a.package, 'rm', '-f', 'files/language-network-proof.json')
    adb('shell', 'am', 'start', '-W', '-n', a.package + '/com.eurobuddha.maxima.app.i18n.' + component, '--es', 'mode', mode)
    assert proof(mode)['same_views_and_drafts']
    for code in ['ru', 'zh-Hans', 'en']:
        adb('shell', 'am', 'broadcast', '-a', a.package + '.LANGUAGE_NETWORK_FIXTURE', '-p', a.package, '--es', 'code', code)
        result = proof(mode, code)
        assert result['same_views_and_drafts'], result
        if mode=='manage':assert result['readable_actions'],result
        keys = {
            'diagnostics':[],
            'counters':[],
            'declined':[],
            'overview':['nativeNetwork.connection','nativeNetwork.addConnect','nativeNetwork.manageButton','nativeNetwork.forwardTitle','nativeNetwork.lan','nodePanel.empty'],
            'manage':['nativeNetwork.manage','nativeNetwork.addHostHint','nativeNetwork.builtin','nativeNetwork.builtinHelp','nativeNetwork.reset','nativeNetwork.idle'],
            'pin':['nativeNetwork.location','nativeNetwork.pinHelp','nativeNetwork.pin','nativeNetwork.hostDirectory'],
            'manual':['nativeNetwork.manualTitle','nativeNetwork.manualHelp','nativeNetwork.forwardPhone','nativeNetwork.port','nativeNetwork.publicAuto','nativeNetwork.enableVerify','nativeNetwork.manualOffButton'],
            'connect':['nativeNetwork.addConnectTitle','nativeNetwork.connect','common.cancel'],
            'qr':['nativeNetwork.shareRelay','nativeNetwork.shareText'],
        }[mode]
        labels = result['labels']
        for key in keys:
            expected = catalog[key][code]
            assert any(expected in label or expected.upper() in label for label in labels), (key, result)
        counts={'overview':1,'manage':1,'pin':1,'manual':2,'connect':0,'qr':0,'counters':1,'declined':1,'diagnostics':1}
        assert result['input_count']==counts[mode],result
        if mode=='diagnostics':
            fixed=['asking the router for a public port…', "can't determine your public IP (need internet)", 'conditions no longer met', 'lease renewal failed', 'listener not running', 'manual re-check', 'mapped a port but it was not reachable (staying Tier 1)', 'network changed', 'network changed during mapping (staying Tier 1)', 'network changed during verification (staying Tier 1)', 'not started', 'off (contribution disabled)', 'off (needs Wi-Fi - CGNAT makes cellular pointless)', 'off (needs charging to start)', 'periodic re-verification', 'ready', 'retrying', 'stopping', 'this network has no forwardable public port (staying Tier 1)']
            english={value['en']:value for value in catalog.values() if isinstance(value['en'],str)}
            for value in fixed:assert english[value][code] in labels,(value,result)
            address='203.0.113.44:9536 中文 Русский {count} $1'
            for key in ['desktopNetwork.manualReachable','desktopNetwork.verifyOutside','desktopNetwork.reachableVia','desktopNetwork.leaseRenewed','nativeNetwork.manualUnreachable']:
                expected=catalog[key][code].replace('{address}',address).replace('{via}','upnp').replace('{port}','9536')
                assert expected in labels,(key,result)
            assert 'unknown diagnostic 中文 {count} $1' in labels,result
        if mode in ['counters','declined']:
            expected=catalog['nativeNetwork.contributionCounts'][code]
            for name,value in [('mail',37),('lookups',91),('blobs',123),('receipts',456),('addresses',789)]:expected=expected.replace('{'+name+'}',str(value))
            if mode=='declined':expected+=catalog['nativeNetwork.declinedCount'][code].replace('{count}','22')
            assert expected in labels,result
        if mode=='connect':assert any('relay中文Русский{count}$1:9501' in label for label in labels),result
        if mode=='qr':assert any('relay.example.invalid:9501' in label for label in labels),result
        (screens / (a.package + '-network-' + mode + '-' + code + a.suffix + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', raw=True))
        results.append(result)
        print('PASS', a.package, mode, code, 'same activity/dialog/fields, literal drafts and selection', flush=True)
adb('shell', 'am', 'force-stop', a.package)
(root / 'build/localization' / ('android-network' + a.suffix + '.json')).write_text(
    json.dumps(results, ensure_ascii=False, indent=2) + '\n')
