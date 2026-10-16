#!/usr/bin/env python3
"""Reuse IDE fixture orchestration for real ScriptEditorActivity state/locale checks."""
import argparse,json,subprocess,time
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--serial',required=True)
p.add_argument('--report-tag',choices=['','layout','reference'],default='')
p.add_argument('--font-scale',choices=['1.0','1.5'],default='1.0');p.add_argument('--bottom',action='store_true')
p.add_argument('--modes',nargs='+',default=['form','lint','deploy','deploy-pending','deployed','check-pending','run-pending','run-result','parse-failed','invalid-json','unsafe-json','node-error'])
a=p.parse_args()
if a.serial!='emulator-5582':raise SystemExit('Dedicated emulator required')
root=Path(__file__).resolve().parents[2];catalog=json.loads((root/'localization/catalog.json').read_text())
pkg='com.eurobuddha.parlons.cloud';suffix='' if a.font_scale=='1.0' else '-150';tag=('-bottom' if a.bottom else '')+('-'+a.report_tag if a.report_tag else '');results=[]
def adb(*args,raw=False):
 r=subprocess.run([a.adb,'-s',a.serial,*args],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=120)
 return r.stdout if raw else r.stdout.decode()
def proof(mode,code=None):
 until=time.monotonic()+120
 while time.monotonic()<until:
  try:
   value=json.loads(adb('shell','run-as',pkg,'cat','files/language-script-proof.json'))
   if value['mode']==mode and (code is None or value['locale']==code) and (not a.bottom or code is None or value['bottom']):return value
  except (subprocess.CalledProcessError,json.JSONDecodeError):pass
  time.sleep(.25)
 raise AssertionError('Timed out: '+mode+'/'+str(code))
old=adb('shell','settings','get','system','font_scale').strip()
try:
 adb('shell','settings','put','system','font_scale',a.font_scale)
 for mode in a.modes:
  print('START',mode,flush=True)
  adb('shell','am','force-stop',pkg);adb('shell','run-as',pkg,'rm','-f','files/language-script-proof.json')
  launch=adb('shell','am','start','-W','-n',pkg+'/com.eurobuddha.maxima.app.i18n.LanguageScriptPreviewActivity','--es','mode',mode)
  assert 'Status: ok' in launch,launch
  value=proof(mode);assert value['same_state'],value
  if a.bottom:adb('shell','am','broadcast','-a',pkg+'.LANGUAGE_SCRIPT_FIXTURE','-p',pkg,'--ez','bottom','true')
  for code in ['ru','zh-Hans','en']:
   adb('shell','am','broadcast','-a',pkg+'.LANGUAGE_SCRIPT_FIXTURE','-p',pkg,'--es','code',code)
   value=proof(mode,code);assert value['same_state'],value
   if a.bottom:
    # Translation can increase content height after the initial scroll. Inspect
    # the actual bottom of the new layout, using a fresh proof after scrolling.
    adb('shell','run-as',pkg,'rm','-f','files/language-script-proof.json')
    adb('shell','am','broadcast','-a',pkg+'.LANGUAGE_SCRIPT_FIXTURE','-p',pkg,'--ez','bottom','true')
    value=proof(mode,code);assert value['same_state'],value
   labels=value['labels'];t=lambda key:catalog[key][code]
   for key in ['scriptEditor.title','scriptEditor.check','scriptEditor.run','scriptEditor.deploy','scriptEditor.testOpen']:assert t(key) in labels,value
   assert value['menu']==[{'id':1,'title':t('scriptEditor.reference')},{'id':2,'title':t('nodeIde.docs')}],value
   assert len(value['drafts'])==6 and value['drafts'][0]=='Saved 中文 Русский {name} $1',value
   expected=[]
   if mode in ['check-pending','run-pending','run-result','parse-failed']:
    cmd='runscript script:"RETURN TRUE"'
    if mode!='check-pending':
     for key,raw in zip(['state','prevstate','globals','signatures'],value['drafts'][2:]):cmd+=' '+key+':'+json.dumps(json.loads(raw),ensure_ascii=False,separators=(',',':'))
    expected=[cmd]
   elif mode in ['deploy-pending','deployed']:expected=['newscript trackall:true script:"RETURN TRUE"']
   assert value['commands']==expected,value
   assert value['pending']==mode.endswith('-pending'),value
   if mode=='deploy':
    for key in ['scriptEditor.deployTitle','scriptEditor.deployBody','scriptEditor.trackAll','common.cancel']:assert t(key) in labels,value
    assert value['trackall'] and value['trackall_default_false'],value
   if mode=='reference':
    assert t('scriptEditor.reference') in labels and t('scriptEditor.referenceBody') in labels,value
    assert catalog['scriptEditor.referenceBody']['en']==(root/'portal/src/main/assets/kissvm.txt').read_text()
   if mode=='lint':
    assert t('scriptEditor.lintTitle') in labels,value
    assert any(t('scriptEditor.lintBlockNum') in label and 'bad_name' in label and 'ENDIF' in label for label in labels),value
   if mode.endswith('-pending'):
    key={'check-pending':'checking','run-pending':'running','deploy-pending':'deploying'}[mode]
    assert value['result']==t('scriptEditor.'+key),value
   if mode in ['run-result','parse-failed']:
    assert t('scriptEditor.parseOk' if mode=='run-result' else 'scriptEditor.parseFailed') in value['result'],value
    for raw in ['001.234567890123456789','RAW 中文 Русский {value} $1','RETURN TRUE']:assert raw in value['result'],value
   if mode=='invalid-json':assert value['result'].startswith('✖ '+t('scriptEditor.invalidJson').split('{error}')[0].replace('{field}','state')),value
   if mode=='unsafe-json':assert value['result']=='✖ '+t('scriptEditor.unsafeJson').replace('{field}','state'),value
   if mode=='node-error':assert value['result']=='✖ RAW 中文 Русский {value} $1',value
   if mode=='deployed':assert 'send address:0x000123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef amount:...' in value['result'],value
   (root/'build/localization/screenshots'/('editor-'+mode+'-'+code+tag+suffix+'.png')).write_bytes(adb('exec-out','screencap','-p',raw=True))
   results.append(value);print('PASS',mode,code,flush=True)
finally:
 (root/'build/localization'/('android-script-editor'+tag+suffix+'.json')).write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n')
 adb('shell','am','force-stop',pkg)
 if old=='null':adb('shell','settings','delete','system','font_scale')
 else:adb('shell','settings','put','system','font_scale',old)
