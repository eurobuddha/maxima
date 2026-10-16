#!/usr/bin/env python3
"""Validate hosted node wallet screens in a disposable emulator; never activate a send action."""
import argparse, json, subprocess, sys, time
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--adb', required=True)
p.add_argument('--serial', required=True)
p.add_argument('--package', choices=['com.eurobuddha.parlons.cloud'], default='com.eurobuddha.parlons.cloud')
p.add_argument('--modes', nargs='+')
p.add_argument('--report-tag', choices=['','balances-history','visual-recheck','sheet-regression','send','gallery','gallery-layout','nft','tools','tools-layout','mint','mint-layout','mint-bottom','collection','collection-layout','tabs'], default='')
p.add_argument('--scroll-bottom', action='store_true')
p.add_argument('--font-scale', choices=['1.0','1.5'], default='1.0')
a = p.parse_args()
if a.serial != 'emulator-5582':
    raise SystemExit('Requires the dedicated ParlonsLanguageFixture emulator on port 5582.')
root = Path(__file__).resolve().parents[2]
catalog = json.loads((root / 'localization/catalog.json').read_text())
screens = root / 'build/localization/screenshots'
screens.mkdir(parents=True, exist_ok=True)

def adb(*args, raw=False, quiet=False):
    try:
        result = subprocess.run([a.adb, '-s', a.serial, *args], check=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    except subprocess.CalledProcessError as error:
        if not quiet:
            print(error.stderr.decode(errors='replace'), file=sys.stderr, flush=True)
        raise
    return result.stdout if raw else result.stdout.decode()

def proof(mode, locale=None):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            result = json.loads(adb('shell', 'run-as', a.package, 'cat', 'files/language-node-wallet-proof.json', quiet=True))
            if result['mode'] == mode and (locale is None or result['locale'] == locale):
                return result
        except (subprocess.CalledProcessError, json.JSONDecodeError):
            pass
        time.sleep(.25)
    raise AssertionError('Timed out waiting for ' + mode + '/' + str(locale))

results = []
modes = a.modes or ['coins','receive','settings','coin','progress']
component = 'LanguageNodeWalletPreviewActivity'
suffix = '' if a.font_scale == '1.0' else '-150'
old_scale = adb('shell', 'settings', 'get', 'system', 'font_scale').strip()
try:
    adb('shell', 'settings', 'put', 'system', 'font_scale', a.font_scale)
    for mode in modes:
        print('START', mode, 'text scale', a.font_scale, flush=True)
        adb('shell', 'am', 'force-stop', a.package)
        adb('shell', 'run-as', a.package, 'rm', '-f', 'files/language-node-wallet-proof.json')
        adb('shell', 'am', 'start', '-W', '-n', a.package + '/com.eurobuddha.maxima.app.i18n.' + component, '--es', 'mode', mode)
        assert proof(mode)['same_views_and_drafts']
        for code in ['ru', 'zh-Hans', 'en']:
            adb('shell', 'am', 'broadcast', '-a', a.package + '.LANGUAGE_NODE_WALLET_FIXTURE', '-p', a.package, '--es', 'code', code)
            result = proof(mode, code)
            assert result['same_views_and_drafts'], result
            keys = {
                'collection-hub': ['nodeCollection.collections','nodeMint.tokenHelp','nodeMint.nftHelp'],
                'collection-progress': ['nodeCollection.items','nodeCollection.resume','nodeCollection.buryAll'],
                'collection-images': ['nodeCollection.items','nodeCollection.pausedImages','nodeCollection.pickImage','nodeCollection.resume'],
                'collection-stuck': ['nodeCollection.stuck','nodeCollection.buryAll'],
                'collection-buried': ['nodeCollection.phaseBuried'],
                'collection-done': ['nodeCollection.done'],
                'collection-repost': ['nodeCollection.repostTitle','nodeCollection.repostWarning','nodeCollection.waitLonger','nodeCollection.repost'],
                'collection-form': ['nodeCollection.nameRequired','nodeCollection.itemImages','nodeCollection.review','nodeCollection.baseUrl'],
                'collection-confirm': ['nodeCollection.confirm','nodeCollection.pipeline','nodeCollection.lockedContract','nodeCollection.start'],
                'collection-starting': ['nodeCollection.fetchCreator'],
                'mint-token': ['nodeMint.nameRequired','nodeMint.amountRequired','nodeMint.reviewToken','nodeMint.addMetadata'],
                'mint-nft': ['nodeMint.embedImage','nodeMint.editionsRequired','nodeMint.alwaysSigned','nodeMint.reviewNft'],
                'mint-nft-url': ['nodeMint.imageUrlMode','nodeMint.hostImage','nodeMint.externalImageHelp','nodeMint.reviewNft'],
                'mint-confirm-token': ['nodeMint.confirmToken','nodeMint.warning','nodeMint.mintAction','common.back'],
                'mint-confirm-nft': ['nodeMint.confirmNft','nodeMint.warning','nodeMint.signatureYes','nodeMint.mintNftAction','common.back'],
                'mint-validation': ['nodeMint.reviewToken'],
                'mint-progress': ['nodeMint.minting'],
                'mint-result': ['nodeMint.tokenMinted','nodeMint.tokenMintedHelp','nodeMint.viewBalances','language.close'],
                'tools-split': ['nodeTools.splitTitle','nodeTools.splitCount','nodeSend.split','common.cancel'],
                'tools-distribute': ['nodeTools.distributeTitle','nodeTools.addressCount','nodeTools.amountEach','nodeTools.distribute','common.cancel'],
                'tools-untrack': ['nodeTools.untrackHelp','nodeTools.untrack','common.cancel'],
                'tools-consolidate': ['nodeTools.consolidateTitle','nodeTools.consolidateHelp','nodeWallet.consolidate','common.cancel'],
                'tools-merge': ['nodeTools.mergeTitle','nodeTools.merge','common.cancel'],
                'tools-validation': ['nodeTools.selectMerge'],
                'tools-waiting': [],
                'tools-starting': [],
                'nft-transfer': ['nodeNft.transferHelp','nodeNft.recipient','nodeNft.transferAction','common.back'],
                'nft-bury': ['nodeNft.buryForever','common.back'],
                'nft-transfer-collection': ['nodeGallery.sendCollection','nodeNft.recipient','nodeNft.sendAll','common.back'],
                'nft-bury-collection': ['nodeNft.buryCollectionTitle','nodeNft.buryEverything','common.back'],
                'nft-unstamped': ['nodeNft.cannotTransfer','nodeNft.unstampedTransfer','language.close'],
                'nft-unsafe': ['nodeNft.cannotBury','nodeNft.unsafeBury','language.close'],
                'nft-id': ['nodeNft.refuseCoin','nodeNft.badCoinId','language.close'],
                'nft-collection-id': ['nodeNft.refuseCollection','nodeNft.badTokenId','language.close'],
                'nft-transfer-progress': ['nodeNft.buildingTransfer'],
                'nft-bury-progress': ['nodeNft.buildingBury'],
                'coins': ['nodeWallet.tools','common.clear','nodeWallet.send'],
                'receive': ['wallet.yourAddress','contacts.copyAddress','nodeWallet.nextAddress','nodeWallet.addressPool'],
                'settings': ['nav.settings','nodeWallet.decimals','nodeWallet.precisionHelp','nodeWallet.fullPrecision','nodeWallet.hiddenTokens','nodeWallet.noneHidden','language.close'],
                'coin': ['nodeWallet.coinId','common.address','wallet.tokenId','nodeWallet.createdBlock','nodeWallet.mmrEntry','language.close'],
                'progress': ['nav.wallet','nodeWallet.fetchingAddress','language.close'],
                'balances': ['nodeBalance.search','wallet.sendable','wallet.confirmed','wallet.unconfirmed','androidWallet.locked'],
                'balance-detail': ['wallet.sendable','wallet.confirmed','wallet.unconfirmed','nodeBalance.lockedContracts','nodeBalance.script','nodeBalance.customContract','nodeBalance.hideSource','nodeBalance.tokenCopy','language.close'],
                'history': ['wallet.all','wallet.sent','wallet.received','nodeHistory.self','nodeHistory.export'],
                'history-detail': ['nodeHistory.txpowId','nodeHistory.inputs','nodeHistory.outputs','wallet.transaction','language.close'],
                'export': ['nodeHistory.exportTitle','nodeHistory.exportHelp'],
                'link': ['nodeWallet.openLink','nodeWallet.open','common.cancel'],
                'gallery-grid': ['nodeGallery.collected','nodeGallery.favourites','nodeGallery.search'],
                'gallery-collection': ['nodeGallery.collected','nodeGallery.favourites','nodeGallery.collectionMenu'],
                'gallery-favourites': ['nodeGallery.collected','nodeGallery.favourites','nodeGallery.search'],
                'gallery-empty': ['nodeGallery.emptyFavourites'],
                'gallery-detail': ['nodeGallery.creator','nodeGallery.fullResolution','nodeGallery.unfavourite','nodeGallery.externalUrl','nodeBalance.webValidation','language.close','chat.send'],
                'gallery-stamped': ['nodeGallery.identity','nodeGallery.buryItem','nodeGallery.sendAll','nodeGallery.buryAll','nodeWallet.transfer','language.close'],
                'gallery-unstamped': ['nodeGallery.buryItem','nodeGallery.sendAll','nodeGallery.buryAll','nodeWallet.transfer','language.close'],
                'gallery-actions': ['nodeGallery.collectionHelp','nodeGallery.sendCollection','nodeGallery.buryCollection','language.close'],
                'send-quick': ['nodeSend.quick','nodeSend.coinControl','nodeSend.recipient','nodeSend.splitHint','nodeSend.burnHint','nodeSend.preview'],
                'send-coins': ['nodeSend.quick','nodeSend.coinControl','nodeSend.selectedCoins','nodeSend.totalIn','nodeSend.changeAddress','nodeSend.preview'],
                'send-picker': ['nodeSend.pickToken','common.cancel','wallet.token'],
                'send-confirm-quick': ['nodeSend.confirm','nodeSend.quickWarning','nodeSend.nodeChooses','common.back','nodeSend.send'],
                'send-confirm-coins': ['nodeSend.confirm','nodeSend.signWarning','nodeSend.totalIn','common.back','nodeSend.signPost'],
                'send-validation': ['nodeSend.invalidAddress'],
                'send-progress': ['nodeSend.signingPosting'],
            }[mode]
            labels = result['labels']
            for key in keys:
                expected = catalog[key][code]
                assert expected in labels or expected.upper() in labels, (key, result)
            assert result['input_count'] == 5, result
            if mode.startswith('collection-'):
                checks=result['checks'];row=json.loads(checks['collection_saved'])[0]
                assert row['name']=='Draft 中文 Привет {count} $1' and row['size']==2 and row['postedat']==980,result
                assert row['items'][0]['image']=='QUJDRA==',result
                assert result['commands']==(1 if mode=='collection-starting' else 0),result
                form=mode in ['collection-form','collection-confirm','collection-starting']
                assert len(checks['collection_drafts'])==(8 if form else 0),result
                if form:
                    assert checks['collection_drafts'][0]=='Collection' and '00002' in checks['collection_drafts'],result
                    assert not checks['collection_embed'] and not checks['collection_icon_embed'],result
                    assert json.loads(checks['collection_images'])=={'1':'QUJDRA=='} and checks['collection_icon']=='REVGSA==',result
                if mode in ['collection-stuck','collection-buried']:
                    assert catalog['nodeCollection.done'][code] not in labels and catalog['nodeCollection.resume'][code] not in labels,result
                if mode=='collection-done':assert catalog['nodeCollection.resume'][code] not in labels,result
                if mode=='collection-confirm':assert 'Collection' in labels and 'Not set' in labels and 'https://example.invalid/nft/<i>.png' in labels,result
                if mode=='collection-starting':assert checks['collection_pending_reply'],result
                if mode=='collection-stuck':
                    expected=catalog['nodeCollection.recordGate'][code].replace('{bytes}','17301').replace('{metadata}','8368').replace('{limit}','8367')
                    assert expected in labels,result
                if mode in ['collection-progress','collection-stuck','collection-images']:
                    expected=catalog['nodeCollection.waitingCreate'][code].replace('{blocks}','2').replace('{limit}','20')
                    assert any(expected in label for label in labels),result
            if mode.startswith('mint-'):
                checks=result['checks']
                assert checks['mint_saved']=='[]',result
                assert result['commands']==(2 if mode=='mint-progress' else 1 if 'nft' in mode else 0),result
                assert len(checks['mint_drafts'])==(8 if 'nft' in mode else 10),result
                assert checks['mint_image']==('QUJDRA==' if 'nft' in mode else ''),result
                if 'confirm' in mode:
                    assert 'Not set' in labels and '0.000000000000000001 MINIMA' in labels,result
                    assert catalog['nodeMint.notSet'][code] in labels,result
                    if 'token' in mode:assert 'Token' in labels and '1000000000000' in labels and 'Metadata 中文 Русский $1' in labels,result
                    else: assert 'NFT 中文 Русский' in labels and '22' in labels and 'ipfs://fixture-image' in labels,result
                if mode=='mint-progress':
                    assert checks['mint_pending_reply'],result
                    command=checks['mint_commands'][-1]
                    assert command.startswith('tokencreate name:') and command.endswith(' signtoken:0x'+'abcd1234'*8),result
                    assert ' amount:1000000000000 decimals:16 burn:0.000000000000000001' in command,result
                    metadata=json.loads(command[len('tokencreate name:'):command.index(' amount:')])
                    assert metadata=={'name':'Token','description':'Not set','ticker':'TOK','url':'https://example.invalid/icon','label':'Metadata 中文 Русский $1'},result
                if mode=='mint-validation':
                    error=catalog['nodeMint.fieldError'][code].replace('{field}',catalog['common.name'][code]).replace('{error}',catalog['nodeMint.invalidCharacters'][code])
                    assert error in labels,result
            if mode.startswith('tools-'):
                assert result['commands']==(1 if mode=='tools-starting' else 0),result
                assert result['checks']['tool_inputs']==({'tools-split':1,'tools-distribute':2}.get(mode,0)),result
                assert result['checks']['total']==('0' if mode=='tools-consolidate' else '3.23456789012345679' if mode=='tools-merge' else '1.234567890123456789'),result
                if mode=='tools-waiting':
                    job=json.loads(result['checks']['distribution_job']);saved=json.loads(result['checks']['distribution_saved'])
                    assert job==saved and job['per']=='0.012345678901234567' and job['retryCount']==2 and len(job['remaining'])==22,result
                    for key,values in [('nodeTools.distributionProgress',{'done':'2','left':'22'}),('nodeTools.waiting',{}),('nodeTools.retry',{'attempt':'2','limit':'3'})]:
                        expected=catalog[key][code]
                        for name,value in values.items():expected=expected.replace('{'+name+'}',value)
                        assert any(expected in label for label in labels),result
                if mode=='tools-starting':
                    assert result['checks']['tool_pending_reply'] and result['checks']['distribution_starting'],result
                    assert catalog['nodeTools.fetchingCount'][code].replace('{count}','22') in labels,result
                if mode=='tools-merge':
                    assert catalog['nodeTools.mergeHelp'][code].replace('{token}','Wallet 中文 Русский').replace('{count}','2') in labels,result
            if mode.startswith('nft-'):
                draft='Draft 中文 Привет {count} $1'
                form=mode in ['nft-transfer','nft-bury','nft-transfer-collection','nft-bury-collection']
                assert result['checks']['nft_inputs']==(1 if form else 0),result
                assert result['commands']==(8 if mode.endswith('-progress') else 0),result
                if form: assert draft in labels,result
                if mode.endswith('-progress'):
                    assert result['checks']['nft_pending_reply'],result
                    commands=result['checks']['nft_commands']
                    assert commands[-1].startswith('txnsign ') and not any(c.startswith('txnpost ') for c in commands),result
                    assert any('amount:1.234567890123456789' in c for c in commands),result
                    assert any('port:0 value:007' in c for c in commands),result
                    assert any('port:1 value:[QUJDRA==]' in c for c in commands),result
                if mode in ['nft-bury','nft-bury-collection','nft-transfer-collection']:
                    key={'nft-bury':'nodeNft.buryWarning','nft-bury-collection':'nodeNft.buryCollectionWarning','nft-transfer-collection':'nodeNft.transferCollectionHelp'}[mode]
                    expected=catalog[key][code].replace('{name}',draft+' #007' if mode=='nft-bury' else draft).replace('{collection}',draft)
                    assert expected in labels,result
            if mode.startswith('gallery-'):
                assert result['commands']==0 and result['checks']['gallery_search']=='0x',result
                count={'gallery-grid':3,'gallery-collection':22,'gallery-favourites':2,'gallery-empty':0,'gallery-detail':3,'gallery-stamped':22,'gallery-unstamped':22,'gallery-actions':22}[mode]
                assert result['checks']['gallery_items']==count,result
                if mode=='gallery-grid':
                    form='few' if code=='ru' else 'other'
                    expected=catalog['nodeGallery.ownedItems'][code][form].replace('{count}','22')
                    assert expected in labels and 'Collection' in labels and 'Token' in labels,result
                if mode=='gallery-detail':
                    assert result['checks']['gallery_copied_token']=='0x'+'dcba4321'*8,result
                    assert 'Token' in labels and 'Creator 中文 Русский' in labels and 'Draft 中文 Привет {count} $1' in labels,result
                    assert any(catalog['nodeGallery.webValidated'][code] in label for label in labels),result
                if mode=='gallery-stamped':
                    assert 'Collection #7' in labels,result
                    assert catalog['nodeGallery.sealed'][code].replace('{index}','7') in labels,result
                if mode=='gallery-unstamped':
                    assert catalog['nodeGallery.unstampedName'][code].replace('{name}','Collection') in labels,result
                if mode in ['gallery-collection','gallery-actions']:
                    assert result['checks']['gallery_collection']=='0x'+'abcd9876'*8,result
            if mode.startswith('send-'):
                assert result['checks']['quick_token']=='0x01' and result['checks']['change_touched'], result
                assert not result['checks']['preview_enabled'] and result['commands']==(4 if mode=='send-progress' else 0), result
                if mode=='send-progress': assert result['checks']['same_pending_sign_reply'], result
                if mode in ['send-picker','send-quick']:
                    assert '1.234567890123456789' in labels and 'Token  ·  TEST' in labels, result
                if mode == 'send-confirm-quick':
                    assert any(catalog['nodeSend.yours'][code] in label for label in labels), result
                    assert '1.234567890123456789 Token' in labels and '0.000000000000000001 MINIMA' in labels, result
                    form='many' if code=='ru' else 'other'
                    assert catalog['nodeSend.equalCoins'][code][form].replace('{count}','12') in labels, result
                if mode == 'send-confirm-coins':
                    assert any(catalog['nodeSend.verify'][code] in label for label in labels), result
                    assert '1.034567890123456788 Wallet 中文 Русский' in labels and '0.2 Wallet 中文 Русский' in labels, result
            if mode == 'coin':
                assert '0x'+'abcd1234'*8 in labels and 'Draft 中文 Привет {count} $1' in labels, result
                assert '1.234567890123456789  Wallet 中文 Русский' in labels, result
            if mode == 'balance-detail':
                assert 'Token' in labels and 'RETURN FALSE' in labels and 'Draft 中文 Привет {count} $1' in labels, result
                assert '1.234567890123456789' in labels and '1.000000000000000001' in labels, result
            if mode == 'balances':
                assert 'Token' in labels and catalog['wallet.token'][code].upper() in labels, result
            if mode == 'history-detail':
                assert '0x'+'cdef1234'*8 in labels and '0x'+'abcd1234'*8 in labels, result
                assert any('−1.234567890123456789 Wallet 中文 Русский' in label for label in labels), result
                assert catalog['nodeHistory.yours'][code] in labels, result
            if mode == 'history':
                value=catalog['nodeHistory.split'][code]
                form='few' if code=='ru' else 'other'
                assert any(value[form].replace('{count}','2') in label for label in labels), result
            if mode == 'link':
                assert catalog['nodeWallet.linkSource'][code].replace('{url}','https://example.invalid/中文?name=Русский&literal={count}') in labels, result
            if mode == 'receive':
                assert '0x'+'abcd1234'*8 in labels, result
                assert any(catalog['nodeWallet.belongs'][code] in label for label in labels), result
            if a.scroll_bottom:
                # Dedicated 720x1600 fixture display: drag only inside the form body.
                for _ in range(3): adb('shell', 'input', 'swipe', '360', '1250', '360', '450', '350')
                time.sleep(.4)
            (screens / (a.package + '-node-wallet-' + mode + '-' + code + suffix + ('-bottom' if a.scroll_bottom else '') + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', raw=True))
            result['font_scale'] = a.font_scale
            results.append(result)
            print('PASS', a.package, mode, code, 'same activity/dialog/fields, literal drafts and selection', flush=True)
finally:
    try:
        adb('shell', 'am', 'force-stop', a.package)
    finally:
        if old_scale == 'null':
            adb('shell', 'settings', 'delete', 'system', 'font_scale')
        else:
            adb('shell', 'settings', 'put', 'system', 'font_scale', old_scale)
(root / 'build/localization' / ('android-node-wallet-' + a.package + ('-'+a.report_tag if a.report_tag else '') + suffix + '.json')).write_text(
    json.dumps(results, ensure_ascii=False, indent=2) + '\n')
