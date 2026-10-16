#!/usr/bin/env python3
"""Measure actual Android shell tabs and retained drafts on the isolated language emulator."""
import argparse, json, re, subprocess, time
import xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser()
p.add_argument('--adb',required=True)
p.add_argument('--serial',required=True)
p.add_argument('--suffix',default='')
p.add_argument('--gestures',action='store_true')
a=p.parse_args()
assert a.serial=='emulator-5582'
root=Path(__file__).resolve().parents[2]
catalog=json.loads((root/'localization/catalog.json').read_text())
screens=root/'build/localization/screenshots'
screens.mkdir(parents=True,exist_ok=True)
def adb(*args,raw=False):
    out=subprocess.run([a.adb,'-s',a.serial,*args],check=True,capture_output=True,timeout=60).stdout
    return out if raw else out.decode()
assert 'ParlonsLanguageFixture' in adb('emu','avd','name')
results=[]
for package,kind in [('com.eurobuddha.maxima.app','Network'),('com.eurobuddha.parlons.cloud','Node')]:
    proof_file='files/language-'+kind.lower()+'-proof.json'
    def proof(sequence):
        deadline=time.monotonic()+90
        while time.monotonic()<deadline:
            try:
                value=json.loads(adb('shell','run-as',package,'cat',proof_file))
                if value.get('sequence')==sequence and 'tabs' in value:return value
            except (subprocess.CalledProcessError,json.JSONDecodeError):pass
            time.sleep(.25)
        raise AssertionError('Timed out: '+package+'/'+str(sequence))
    adb('shell','am','force-stop',package)
    adb('shell','run-as',package,'rm','-f',proof_file)
    adb('shell','am','start','-W','-n',package+'/com.eurobuddha.maxima.app.i18n.Language'+kind+'PreviewActivity','--es','mode','overview','--ez','tab_check','true')
    assert proof(0)['same_views_and_drafts']
    sequence=0
    previous_tabs=None
    cases=[('language',c) for c in ['ru','zh-Hans','en']]
    cases += [('scroll-left','en'),('scroll-right','en')]
    if a.gestures:cases += [('swipe-left','en'),('swipe-right','en')]
    if kind=='Network':cases += [('settings',c) for c in ['ru','zh-Hans','en']]
    for action,code in cases:
        sequence+=1
        extras=[]
        if action=='settings' and code=='ru':extras=['--ez','settings','true']
        if action.startswith('scroll-'):extras=['--es','scroll',action.split('-')[1]]
        if action.startswith('swipe-') and previous_tabs['overflow']>0:
            adb('shell','uiautomator','dump','/sdcard/parlons-tabs.xml')
            tree=ET.fromstring(adb('shell','cat','/sdcard/parlons-tabs.xml'))
            bounds=next(n.attrib['bounds'] for n in tree.iter('node') if n.attrib.get('resource-id')==package+':id/tabs')
            left,top,right,bottom=map(int,re.findall(r'\d+',bounds))
            x1=left+(right-left)//4; x2=right-(right-left)//4; y=(top+bottom)//2
            if action=='swipe-right':x1,x2=x2,x1
            adb('shell','input','swipe',str(x1),str(y),str(x2),str(y),'600')
        adb('shell','am','broadcast','-a',package+'.LANGUAGE_'+kind.upper()+'_FIXTURE','-p',package,'--es','code',code,'--ei','sequence',str(sequence),*extras)
        value=proof(sequence); tabs=value['tabs']
        assert value['same_views_and_drafts'],value
        assert value['locale']==code,value
        assert tabs['count']==(5 if kind=='Network' else 4),tabs
        assert tabs['page']==tabs['selected']==tabs['expected'],tabs
        assert tabs['selections']==tabs['reselections']==0,tabs
        assert tabs['settings_hidden'],tabs
        for row in tabs['labels']:assert row['readable'],tabs
        for row in tabs['header']:assert row['fits'] and row['visible'],tabs
        assert tabs['header'][1]['text']==catalog['common.powered'][code],tabs
        if kind=='Network' and action=='language':
            assert tabs['network_heading']['fits'] and tabs['network_heading']['lines']==1,tabs
        if action=='language':assert tabs['selected_visible'],tabs
        if action.startswith(('scroll-','swipe-')) and previous_tabs is not None:
            side=action.split('-')[1]
            if previous_tabs[side]['visible']:
                assert (tabs['scroll_x']<previous_tabs['scroll_x'] if side=='left' else tabs['scroll_x']>previous_tabs['scroll_x']),(previous_tabs,tabs)
        previous_tabs=tabs
        for side in ['left','right']:
            key='nav.'+('previousTabs' if side=='left' else 'nextTabs')
            assert tabs[side]['description']==catalog[key][code],(key,tabs)
        expected_mode=0 if tabs['font_scale']>1.0 or tabs['width_dp']<400 else 1
        assert tabs['mode']==expected_mode,tabs
        value['package']=package;value['action']=action
        results.append(value)
        if action in ['language','settings']:
            (screens/(package+'-tabs-'+action+'-'+code+a.suffix+'.png')).write_bytes(adb('exec-out','screencap','-p',raw=True))
        print('PASS',package,action,code,tabs['width_dp'],tabs['font_scale'],flush=True)
    adb('shell','am','force-stop',package)
(root/'build/localization'/('android-tabs'+a.suffix+'.json')).write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n')
