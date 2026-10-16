#!/usr/bin/env python3
"""IDE locale continuity checks on the dedicated, unpaired debug emulator."""
import argparse,json,subprocess,time
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--serial',required=True)
p.add_argument('--report-tag',choices=['','layout','terminal','terminal-tail','scripts','scripts-layout','txn','txn-tail','help'],default='')
p.add_argument('--font-scale',choices=['1.0','1.5'],default='1.0')
p.add_argument('--modes',nargs='+',default=['shell','about','export','logs-empty','logs-raw','logs-error','pending'])
a=p.parse_args()
if a.serial!='emulator-5582':raise SystemExit('Dedicated emulator required')
root=Path(__file__).resolve().parents[2];catalog=json.loads((root/'localization/catalog.json').read_text())
pkg='com.eurobuddha.parlons.cloud';suffix='' if a.font_scale=='1.0' else '-150';results=[]
def adb(*args,raw=False):
 r=subprocess.run([a.adb,'-s',a.serial,*args],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=120)
 return r.stdout if raw else r.stdout.decode()
def proof(mode,code=None):
 until=time.monotonic()+120
 while time.monotonic()<until:
  try:
   v=json.loads(adb('shell','run-as',pkg,'cat','files/language-ide-proof.json'))
   if v['mode']==mode and (code is None or v['locale']==code):return v
  except (subprocess.CalledProcessError,json.JSONDecodeError):pass
  time.sleep(.25)
 raise AssertionError('Timed out: '+mode+'/'+str(code))
old=adb('shell','settings','get','system','font_scale').strip()
try:
 adb('shell','settings','put','system','font_scale',a.font_scale)
 for mode in a.modes:
  print('START',mode,flush=True)
  adb('shell','am','force-stop',pkg)
  adb('shell','run-as',pkg,'rm','-f','files/language-ide-proof.json')
  launch=adb('shell','am','start','-W','-n',pkg+'/com.eurobuddha.maxima.app.i18n.LanguageIdePreviewActivity','--es','mode',mode)
  assert 'Status: ok' in launch,launch
  v=proof(mode);assert v['same_state'],v
  for code in ['ru','zh-Hans','en']:
   adb('shell','am','broadcast','-a',pkg+'.LANGUAGE_IDE_FIXTURE','-p',pkg,'--es','code',code)
   v=proof(mode,code);assert v['same_state'],v
   labels=v['labels'];t=lambda key:catalog[key][code]
   assert v['tabs']==[t('nodeIde.'+k) for k in ['terminal','scripts','transactions','logs']],v
   assert t('nodeIde.block').replace('{block}','000123456789') in labels,v
   expected={1:'nodeIde.clear',5:'nodeIde.selectOutput' if mode.startswith(('logs-','terminal-','scripts-','txn-')) else 'nodeIde.exitSelect',2:'nativeSettings.about',3:'nodeIde.export',4:'nodeIde.docs',6:'nodeIde.copyOutput'}
   for item in v['menu']:
    if item['id'] in expected:assert item['title']==t(expected[item['id']]),v
   if mode=='about':assert t('nodeIde.about') in labels,v
   if mode=='export':
    for key in ['nodeIde.saveFile','nativeContacts.share','nodeIde.copyClipboard','common.cancel']:assert t(key) in labels,v
   if mode=='logs-empty':assert t('nodeIde.logEmpty') in labels,v
   if mode=='logs-error':assert t('nodeIde.logFailed').replace('{error}','RAW 中文 Русский {count} $1') in labels,v
   if mode=='logs-raw':assert any('"amount":"1.234567890123456789"' in x and x.endswith('\nSaved\n') for x in labels),v
   expected_draft='txnoutput id:demo ' if mode in ['terminal-completion','terminal-help-params'] else 'help command:ba' if mode=='terminal-help-completion' else 'status 中文 Русский {count} $1'
   assert v['draft']==expected_draft,v
   if mode.startswith('terminal-') or mode=='pending':
    for key in ['terminal.run','terminal.copyAll','terminal.export','common.done']:assert t(key) in labels,v
    assert v['highlighted']==1,v
    checks={
     'terminal-banner':['terminal.banner'],
     'terminal-favorite':['terminal.saveFavorite','common.save','common.cancel'],
     'terminal-history':['terminal.history','language.close'],
     'terminal-favorites':['terminal.favorites','terminal.deleteAction','language.close'],
     'terminal-block':['terminal.outputBlock','terminal.copyBlock','terminal.selectBlock','terminal.shareBlock','terminal.copySession','terminal.selectionMode'],
     'terminal-select':['terminal.selectCopy','terminal.copyAll','language.close'],
     'terminal-warning':['terminal.risky','terminal.warnHistory','terminal.runAnyway','common.cancel'],
     'pending':['terminal.running']}
    for key in checks.get(mode,[]):assert t(key) in labels,v
    if mode=='terminal-completion':assert any(t('terminal.required') in label for label in labels),v
    if mode=='terminal-favorites':assert 'Saved' in labels,v
    if mode=='terminal-favorite':assert v['dialog_inputs']==1,v
    if mode=='terminal-history':assert expected_draft in labels,v
    if mode=='terminal-result':
     assert '1.234567890123456789' in v['output'] and catalog['terminal.failed']['en'] in v['output'],v
     assert t('terminal.running') not in labels,v
   if mode.startswith('terminal-help-'):
    raw='{"status":true,"message":"Saved 中文 Русский {count} $1","amount":"1.234567890123456789"}'
    assert raw in v['output'],v
    if mode=='terminal-help-dialog':assert t('commandHelp.txnoutputPage') in labels,v
    if mode=='terminal-help-result':
     assert t('commandHelp.txnoutputPage') in v['output'] and t('commandHelp.txnoutputBrief') in v['output'],v
     assert '[id:] [amount:] [address:] (tokenid:) (storestate:true|false)' in v['output'],v
     assert 'mutated' not in v['output'],v
    if mode=='terminal-help-list':
     for key in ['statusBrief','txnoutputBrief']:assert t('commandHelp.'+key) in v['output'],v
     assert 'unknown' in v['output'] and raw in v['output'].split('unknown',1)[1],v
    if mode=='terminal-help-completion':assert any(t('commandHelp.balanceBrief') in label for label in labels),v
    if mode=='terminal-help-params':
     for key in ['detail33','detail34']:
      assert any(t('commandHelp.'+key) in label for label in labels),v
    if mode=='terminal-help-unknown':assert v['output'].count(raw)==3 and 'mutated' not in v['output'],v
   if mode.startswith('scripts-'):
    for key in ['scripts.new','scripts.onNode']:assert t(key) in labels,v
    checks={
      'scripts-empty':['scripts.empty'],
      'scripts-templates':['scripts.fromTemplate','scripts.blank','scripts.signature','scripts.timelock','scripts.hashlock','scripts.multisig','scripts.random','scripts.covenant'],
      'scripts-name':['scripts.nameTitle','scripts.create','common.cancel'],
      'scripts-actions':['scripts.open','scripts.duplicate','scripts.delete'],
      'scripts-delete':['scripts.deleteBody','scripts.delete','common.cancel'],
      'scripts-node':['scripts.unreadable'],
      'scripts-import':['scripts.import','contacts.copyAddress','common.cancel']}
    for key in checks.get(mode,[]):assert t(key) in labels,v
    if mode=='scripts-name':assert v['dialog_inputs']==1,v
    if mode=='scripts-actions':assert 'Saved' in labels,v
    if mode=='scripts-list':
     count=len('/* 中文 Русский {count} $1 */\nRETURN TRUE')
     form=t('scripts.characters')['many' if code=='ru' else 'other'].replace('{count}',str(count))
     assert any(label.startswith('Saved\n'+form+' · ') for label in labels),v
    if mode=='scripts-delete':assert t('scripts.deleteTitle').replace('{name}','Saved') in labels,v
    if mode=='scripts-import':assert '/* 中文 Русский {count} $1 */\nRETURN TRUE' in labels,v
    if mode=='scripts-node':assert t('scripts.nodeTitle').replace('{count}','2') in labels,v
   if mode.startswith('txn-'):
    tx=v['transaction'];tid='txn中文Русский_{count}$1';amount='001.234567890123456789'
    address='0x0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';raw='RAW 中文 Русский {field} $1'
    def rendered(key,**arguments):
     text=t('txnWorkbench.'+key)
     for name,value in arguments.items():text=text.replace('{'+name+'}',str(value))
     return text
    for key in ['flow','input','output','change','state','sign','basics','post','listAll']:assert t('txnWorkbench.'+key) in labels,v
    expected=[]
    if mode=='txn-coins':expected=['coins relevant:true sendable:true simplestate:true']
    elif mode in ['txn-summary','txn-change','txn-change-pending','txn-change-failed']:expected=['txncheck id:'+tid]
    elif mode=='txn-list':expected=['txnlist']
    elif mode=='txn-export':expected=['txnexport id:'+tid]
    elif mode=='txn-command-error':expected=['txncreate id:'+tid]
    if mode=='txn-change-failed':expected+=['getaddress','txnoutput id:'+tid+' amount:'+amount+' address:'+address+' storestate:false','txnoutput id:'+tid+' amount:2.000000000000000001 address:'+address+' tokenid:'+address+' storestate:false']
    assert tx['commands']==expected,v
    assert tx['pending']==(mode=='txn-change-pending'),v
    assert tx['change_guard']==(mode in ['txn-change','txn-change-pending']),v
    assert tx['drafts'][0]==('' if mode=='txn-empty' else tid),v
    fields={'txn-output':[amount,address,'0x00'],'txn-state':['006','0x0001'],'txn-sign':[address],
            'txn-post':['0.000000000000000001'],'txn-import':['0x0000123456789abcdef']}
    assert tx['drafts'][1:]==fields.get(mode,[]),v
    checks={'txn-output':['outputTitle','outputBody','storestate'],'txn-state':['stateTitle','stateBody'],
            'txn-sign':['signTitle','signBody'],'txn-post':['postTitle','postBody'],'txn-import':['importTitle'],
            'txn-delete':['deleteBody'],'txn-change':['changeTitle','addChange'],'txn-empty':['help','empty']}
    for key in checks.get(mode,[]):assert t('txnWorkbench.'+key) in labels,v
    if mode=='txn-output':assert tx['storestate'] and tx['storestate_default_false'],v
    if mode=='txn-delete':assert rendered('deleteTitle',id=tid) in labels,v
    if mode=='txn-coins':assert rendered('coinTitle',amount=1) in labels and any(amount+' MINIMA' in label for label in labels),v
    if mode=='txn-change':assert rendered('changeBody',amounts=amount+' MINIMA\n') in labels,v
    if mode=='txn-change-pending':assert tx['status']==t('txnWorkbench.calculatingChange'),v
    if mode=='txn-change-failed':assert tx['status']==rendered('changeFailed',index=2,total=2,error=raw,amounts='2.000000000000000001 '+address[:12]+'…'+address[-4:]+'\n'),v
    if mode=='txn-command-error':assert tx['status']==rendered('commandError',command='txncreate id:'+tid,error=raw),v
    if mode=='txn-export':assert tx['clipboard']=='0x0000123456789abcdef' and tx['status']==rendered('exported',amount=len(tx['clipboard'])),v
    if mode=='txn-node-error':assert tx['status']=='✖ '+raw,v
    if mode=='txn-list':assert tx['status']==rendered('pendingTitle',amount=1)+'• '+tid+'  '+rendered('ioCounts',inputs=1,outputs=2)+'\n\n'+t('txnWorkbench.inspectHint'),v
    if mode=='txn-summary':
     expected_summary=rendered('balanceLine',token='MINIMA',input='002.234567890123456789',output='1',difference=amount)
     expected_summary+=rendered('burnLine',amount='0.000000000000000001')+rendered('validLine',basic='true',signatures='false',proofs='true',scripts='false')+'\n'+t('txnWorkbench.burnWarning')
     assert tx['summary']==expected_summary,v
   assert v['commands']==(len(v['transaction']['commands']) if mode.startswith('txn-') else 1 if mode.startswith('logs-') or mode in ['pending','terminal-result','scripts-node','scripts-pending','terminal-help-result','terminal-help-list','terminal-help-unknown'] else 0),v
   assert v['pending']==(mode in ['pending','scripts-pending']),v
   (root/'build/localization/screenshots'/('ide-'+mode+'-'+code+suffix+'.png')).write_bytes(adb('exec-out','screencap','-p',raw=True))
   results.append(v);print('PASS',mode,code,flush=True)
finally:
 (root/'build/localization'/('android-ide'+('-'+a.report_tag if a.report_tag else '')+suffix+'.json')).write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n')
 adb('shell','am','force-stop',pkg)
 if old=='null':adb('shell','settings','delete','system','font_scale')
 else:adb('shell','settings','put','system','font_scale',old)
