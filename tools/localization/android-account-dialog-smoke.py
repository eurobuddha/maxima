#!/usr/bin/env python3
"""Check real account dialogs with synthetic fields, without invoking account mutations."""
import argparse,json,subprocess,time
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--serial',required=True);p.add_argument('--package',required=True);p.add_argument('--suffix',default='');p.add_argument('--modes',nargs='+');a=p.parse_args()
assert a.serial=='emulator-5582'
assert a.package in ['com.eurobuddha.maxima.app','com.eurobuddha.parlons.cloud']
root=Path(__file__).resolve().parents[2];catalog=json.loads((root/'localization/catalog.json').read_text())
def adb(*args,raw=False):
 r=subprocess.run([a.adb,'-s',a.serial,*args],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=45)
 return r.stdout if raw else r.stdout.decode()
def proof(mode,locale=None):
 end=time.monotonic()+60
 while time.monotonic()<end:
  try:
   value=json.loads(adb('shell','run-as',a.package,'cat','files/language-account-dialog-proof.json'))
   if value['mode']==mode and (locale is None or value['locale']==locale):return value
  except (subprocess.CalledProcessError,json.JSONDecodeError):pass
  time.sleep(.25)
 raise AssertionError('Missing proof '+mode+'/'+str(locale))
modes=a.modes or (['backup'] if a.package.endswith('maxima.app') else ['relays','cloud-backup','seed-warning','seed-reveal','identity-off','identity-on'])
results=[]
for mode in modes:
 adb('shell','am','force-stop',a.package)
 adb('shell','run-as',a.package,'rm','-f','files/language-account-dialog-proof.json')
 adb('shell','am','start','-W','-n',a.package+'/com.eurobuddha.maxima.app.i18n.LanguageAccountDialogsPreviewActivity','--es','mode',mode)
 assert proof(mode)['same_state']
 for code in ['ru','zh-Hans','en']:
  adb('shell','am','broadcast','-a',a.package+'.LANGUAGE_ACCOUNT_DIALOG_FIXTURE','-p',a.package,'--es','code',code)
  result=proof(mode,code);assert result['same_state'],result
  keys={
   'relays':['nativeSettings.relaySources','nativeSettings.useBuiltins','nativeSettings.relayApply','nativeDialog.relayInput','nativeSettings.scanRelay','common.add','common.done','nativeDialog.reset'],
   'backup':['backup.title','backup.warning','backup.password','backup.repeatPassword','common.ok','common.cancel'],
   'cloud-backup':['nativeDialog.backupAccount','nativeDialog.backupWarning','nativeDialog.backupPassphrase','nativeDialog.repeatPassphrase','nativeDialog.createBackup','common.cancel'],
   'seed-warning':['nativeDialog.showSeedQuestion','nativeDialog.cloudSeedWarning','nativeDialog.show','common.cancel'],
   'seed-reveal':['nativeDialog.yourWords','common.copy','language.close'],
   'identity-off':['identityImport.confirm','identityImport.confirmHelp','identityImport.stopped','identityImport.restart','identityImport.later'],
   'identity-on':['identityImport.confirm','identityImport.confirmHelp','identityImport.stopped','identityImport.restart','identityImport.later'],
  }[mode]
  for key in keys:assert catalog[key][code] in result['labels'],(key,result)
  assert result['input_count']==(2 if mode in ['backup','cloud-backup'] else 1 if mode=='relays' else 0),result
  if mode.startswith('identity-'):assert 'Owner 中文 Русский {count} $1\n\nMAX#Fixture中文Русский{count}$1' in result['labels'],result
  if mode not in ['seed-reveal','identity-off','identity-on']:
   (root/'build/localization/screenshots'/(a.package+'-account-'+mode+'-'+code+a.suffix+'.png')).write_bytes(adb('exec-out','screencap','-p',raw=True))
  results.append(result);print('PASS',a.package,mode,code,'same dialog, drafts and action gates',flush=True)
adb('shell','am','force-stop',a.package)
(root/'build/localization'/('android-account-dialog-'+a.package+a.suffix+'.json')).write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n')
