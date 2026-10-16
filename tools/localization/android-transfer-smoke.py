#!/usr/bin/env python3
"""Run the debug-only real-upload UI against a synthetic backend in an isolated emulator."""
import argparse,json,re,subprocess,xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--serial',required=True);p.add_argument('--package',required=True);a=p.parse_args()
if not a.serial.startswith('emulator-'):raise SystemExit('An isolated emulator is required.')
root=Path(__file__).resolve().parents[2];catalog=json.loads((root/'localization/catalog.json').read_text())
def adb(*args,raw=False):
 r=subprocess.run([a.adb,'-s',a.serial,*args],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=45)
 return r.stdout if raw else r.stdout.decode()
def ui():
 adb('shell','uiautomator','dump','/data/local/tmp/transfer.xml')
 return ET.fromstring(adb('shell','cat','/data/local/tmp/transfer.xml'))
def find(t,attr,value):
 for n in t.iter('node'):
  if n.get(attr)==value:return n
 raise AssertionError('Missing '+attr+': '+value)
def tap(n):
 x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')));adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
adb('shell','am','start','-W','-n',a.package+'/com.eurobuddha.maxima.app.i18n.LanguageTransferPreviewActivity')
t=ui()
# Find Hide in whichever language was explicitly selected previously.
for locale in ['en','zh-Hans','ru']:
 try:hide=find(t,'text',catalog['files.hide'][locale]);break
 except AssertionError:continue
else:raise AssertionError('Upload dialog not shown')
tap(hide);t=ui();instance=find(t,'resource-id','android:id/text1').get('text')
assert find(t,'resource-id','android:id/text2').get('text')=='1/1/0/0/49152'
for locale,autonym in [('ru','Русский'),('zh-Hans','简体中文')]:
 tap(find(ui(),'resource-id','android:id/button1'));tap(find(ui(),'text',autonym));t=ui()
 assert find(t,'resource-id','android:id/text1').get('text')==instance,'Activity restarted during upload'
 assert find(t,'resource-id','android:id/text2').get('text')=='1/1/0/0/49152','Upload cancelled or duplicated'
 print('PASS held upload preserved through '+locale,flush=True)
tap(find(t,'resource-id','android:id/button2'))
t=ui();find(t,'text','Chats 中文 Привет.bin')
# The final transfer sheet translates the state, without changing its protocol identifier.
status=next((n.get('text') for n in t.iter('node') if n.get('text','').startswith(catalog['files.readySharing']['zh-Hans'])),None)
assert status,'Translated final transfer status missing'
tap(find(t,'text',catalog['language.close']['zh-Hans']));t=ui()
assert find(t,'resource-id','android:id/text1').get('text')==instance
assert find(t,'resource-id','android:id/text2').get('text')=='1/3/1/0/102400','Unexpected upload counts or bytes'
result={'package':a.package,'locales':['ru','zh-Hans'],'same_activity':True,'begin':1,'append':3,'finish':1,'cancel':0,'bytes_verified':102400,'filename':'Chats 中文 Привет.bin'}
(root/'build/localization'/('android-transfer-'+a.package+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
adb('shell','am','force-stop',a.package)
print('PASS one upload completed, all bytes and filename unchanged',flush=True)
