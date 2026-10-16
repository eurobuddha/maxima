#!/usr/bin/env python3
"""Check real Explain dialogs in both Android apps."""
import argparse,json,subprocess,time
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--serial',required=True);p.add_argument('--package',required=True);p.add_argument('--suffix',default='');p.add_argument('--topics',nargs='+');a=p.parse_args()
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
   value=json.loads(adb('shell','run-as',a.package,'cat','files/language-help-proof.json'))
   if value['topic']==mode and (locale is None or value['locale']==locale):return value
  except (subprocess.CalledProcessError,json.JSONDecodeError):pass
  time.sleep(.25)
 raise AssertionError('Missing proof '+mode+'/'+str(locale))
import re
source=(root/'app/src/main/java/com/eurobuddha/maxima/app/ui/Explain.java').read_text()
entries={topic:(title,body) for topic,title,body in re.findall(r'put\("([^"]+)", Language.msg\(R.string.(\w+)\), Language.msg\(R.string.(\w+)\)\);',source)}
assert len(entries)==25
resource_keys={'l10n_'+re.sub(r'(?<!^)(?=[A-Z])','_',key.replace('.','_')).lower():key for key in catalog}
modes=a.topics or list(entries)
results=[]
for mode in modes:
 adb('shell','am','force-stop',a.package)
 adb('shell','run-as',a.package,'rm','-f','files/language-help-proof.json')
 adb('shell','am','start','-W','-n',a.package+'/com.eurobuddha.maxima.app.i18n.LanguageHelpPreviewActivity','--es','topic',mode)
 initial=proof(mode);assert initial['same_dialog']
 for code in ['ru','zh-Hans','en']:
  adb('shell','am','broadcast','-a',a.package+'.LANGUAGE_HELP_FIXTURE','-p',a.package,'--es','code',code)
  result=proof(mode,code);assert result['same_dialog'] and result['instance']==initial['instance'],result
  keys=[resource_keys[resource] for resource in entries[mode]]+['help.gotIt']
  for key in keys:assert catalog[key][code] in result['labels'],(key,result)
  if a.topics or mode in ['status','direct','battery','seed','relays']:
   (root/'build/localization/screenshots'/(a.package+'-help-'+mode+'-'+code+a.suffix+'.png')).write_bytes(adb('exec-out','screencap','-p',raw=True))
  results.append(result);print('PASS',a.package,mode,code,'same dialog and full translated help',flush=True)
adb('shell','am','force-stop',a.package)
(root/'build/localization'/('android-help-'+a.package+a.suffix+'.json')).write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n')
