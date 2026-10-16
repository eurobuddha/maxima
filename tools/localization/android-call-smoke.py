#!/usr/bin/env python3
"""Real call screen/renderers with synthetic state; no microphone, camera, peer or signaling."""
import argparse,json,re,subprocess,xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--serial',required=True);p.add_argument('--package',required=True);a=p.parse_args()
if not a.serial.startswith('emulator-'):raise SystemExit('An isolated emulator is required.')
root=Path(__file__).resolve().parents[2];c=json.loads((root/'localization/catalog.json').read_text())
def adb(*args,raw=False):
 r=subprocess.run([a.adb,'-s',a.serial,*args],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=45)
 return r.stdout if raw else r.stdout.decode()
def ui():
 adb('shell','uiautomator','dump','/data/local/tmp/call.xml')
 return ET.fromstring(adb('shell','cat','/data/local/tmp/call.xml'))
def find(t,attr,value):
 for n in t.iter('node'):
  if n.get(attr)==value:return n
 raise AssertionError('Missing '+value)
def bounds(n):return list(map(int,re.findall(r'\d+',n.get('bounds'))))
def tap(n):
 x,y,r,b=bounds(n);adb('shell','input','tap',str((x+r)//2),str((y+b)//2))
adb('shell','am','force-stop',a.package)
adb('shell','am','start','-W','-n',a.package+'/com.eurobuddha.maxima.app.i18n.LanguageCallPreviewActivity')
t=ui();proof=find(t,'resource-id','android:id/text1').get('text');assert proof.startswith('PASS renderers'),proof
for locale,name in [('ru','Русский'),('zh-Hans','简体中文')]:
 tap(find(ui(),'resource-id','android:id/button1'));tap(find(ui(),'text',name));t=ui()
 assert find(t,'resource-id','android:id/text1').get('text')==proof,'Call screen or renderers were replaced'
 buttons=[find(t,'text',c['nativeCall.'+key][locale]) for key in ['mute','end','speaker','flip']]
 for left,right in zip(buttons,buttons[1:]):assert bounds(left)[2]<=bounds(right)[0],'Call buttons overlap'
 (root/'build/localization/screenshots'/(a.package+'-call-'+locale+'.png')).write_bytes(adb('exec-out','screencap','-p',raw=True))
 print('PASS',a.package,locale,'same native video renderers; all call controls present',flush=True)
(root/'build/localization'/('android-call-'+a.package+'.json')).write_text(json.dumps({'package':a.package,'synthetic_call_state':True,'same_activity_and_renderers':True,'locales':['ru','zh-Hans'],'no_media_capture_or_peer':True},indent=2)+'\n')
adb('shell','am','force-stop',a.package)
