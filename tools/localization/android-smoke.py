#!/usr/bin/env python3
"""Exercise debug-only native language fixtures on an explicitly named isolated emulator."""
import argparse,json,re,subprocess,xml.etree.ElementTree as ET
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--adb',required=True);parser.add_argument('--serial',required=True);parser.add_argument('--package',required=True)
a=parser.parse_args()
if not a.serial.startswith('emulator-'):raise SystemExit('This fixture requires an isolated emulator.')
root=Path(__file__).resolve().parents[2];out=root/'build/localization/screenshots';out.mkdir(parents=True,exist_ok=True)
def adb(*args,raw=False):
 result=subprocess.run([a.adb,'-s',a.serial,*args],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=40)
 return result.stdout if raw else result.stdout.decode()
def ui():
 adb('shell','uiautomator','dump','/data/local/tmp/parlons-language.xml')
 return ET.fromstring(adb('shell','cat','/data/local/tmp/parlons-language.xml'))
def find(tree,attribute,value):
 for node in tree.iter('node'):
  if node.get(attribute)==value:return node
 raise AssertionError('Missing '+attribute+': '+value)
def field(tree,name):
 for node in tree.iter('node'):
  if node.get('resource-id','').endswith('/'+name):return node
 raise AssertionError('Missing field: '+name)
def tap(node):
 x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
 adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
def screenshot(suffix):
 (out/(a.package+'-'+suffix+'.png')).write_bytes(adb('exec-out','screencap','-p',raw=True))
first=ui();instance=field(first,'chat_subtitle').get('text');draft=field(first,'chat_input').get('text')
results=[]
for autonym,code,expected,count in [('Русский','ru','Пароли не совпадают.','2 ретранслятора'),('简体中文','zh-Hans','两次输入的密码不一致。','2 个中继')]:
 tap(field(ui(),'btn_chat_info'));tap(find(ui(),'text',autonym));tree=ui()
 assert field(tree,'chat_subtitle').get('text')==instance,'Activity was recreated: '+str(instance)+' -> '+str(field(tree,'chat_subtitle').get('text'))
 assert field(tree,'chat_input').get('text')==draft,'Draft changed'
 assert field(tree,'chat_title').get('text')=='Chats','User name changed'
 find(tree,'text',expected);find(tree,'text',count)
 screenshot(code);results.append({'locale':code,'same_activity':True,'draft_preserved':True,'plural':count})
 print('PASS',a.package,code,'same activity and draft; native strings and plural verified',flush=True)
adb('shell','am','force-stop',a.package)
adb('shell','am','start','-W','-n',a.package+'/com.eurobuddha.maxima.app.i18n.LanguagePreviewActivity')
reopened=ui();find(reopened,'text','两次输入的密码不一致。')
assert field(reopened,'chat_subtitle').get('text')!=instance,'Relaunch did not create a new activity'
(root/'build/localization'/('android-smoke-'+a.package+'.json')).write_text(json.dumps({'package':a.package,'results':results,'persisted_after_process_restart':True},ensure_ascii=False,indent=2)+'\n')
print('PASS language persisted across process restart',flush=True)
