"""Reply/review output is inserted through the real IME into a separate synthetic app."""
from pathlib import Path
from adb_shell.adb_device import AdbDeviceTcp
import json,os,re,shlex,subprocess,sys,time,xml.etree.ElementTree as E
root=Path(__file__).resolve().parents[1]; ev=root/'build/test-evidence';ev.mkdir(parents=True,exist_ok=True)
pkg='com.rimeai.keyboard.debug'; testpkg=pkg+'.test'
ime=pkg+'/com.osfans.trime.ime.core.TrimeInputMethodService'
port=int(os.environ.get('HUIYI_ADB_PORT','5557'))
d=AdbDeviceTcp('127.0.0.1',port,default_transport_timeout_s=30);d.connect(auth_timeout_s=5)
def shell(cmd):return d.shell(cmd,read_timeout_s=35,transport_timeout_s=35,timeout_s=40)
def nodes():
 if 'dumped to:' not in shell('uiautomator dump /data/local/tmp/insert-window.xml'): return []
 return list(E.fromstring(shell('cat /data/local/tmp/insert-window.xml')).iter('node'))
def find(text=None,desc=None,cls=None,timeout=60):
 end=time.monotonic()+timeout
 while time.monotonic()<end:
  for n in nodes():
   if (text is None or n.get('text')==text) and (desc is None or n.get('content-desc')==desc) and (cls is None or n.get('class')==cls):return n
  time.sleep(.3)
 raise AssertionError('Missing '+ascii(text or desc or cls))
def tap(n):
 x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')));shell(f'input tap {(x1+x2)//2} {(y1+y2)//2}')
def wait_stage(name):
 end=time.monotonic()+180
 while time.monotonic()<end:
  if shell('run-as '+pkg+' cat files/cursor-insertion-stage').strip()==name:return
  if process.poll() is not None:raise AssertionError('Instrumentation ended before '+name)
  time.sleep(.3)
 raise AssertionError('Timeout '+name)
def signal():shell('run-as '+pkg+' touch files/cursor-insertion-continue')
def editor(value=None,start=None,end=None,focus=False,hide_keyboard=False,clipboard=None,read_clip=False):
 cmd='am broadcast -a '+testpkg+'.SET_EDITOR -p '+testpkg
 if value is not None:cmd+=' --es value '+shlex.quote(value)
 if start is not None:cmd+=' --ei start '+str(start)
 if end is not None:cmd+=' --ei end '+str(end)
 if focus:cmd+=' --ez focus true'
 if hide_keyboard:cmd+=' --ez hideKeyboard true'
 if clipboard is not None:cmd+=' --es clipboard '+shlex.quote(clipboard)
 if read_clip:cmd+=' --ez readClipboard true'
 return shell(cmd)
def assert_text(text):
 end=time.monotonic()+60
 while time.monotonic()<end:
  if any(n.get('class')=='android.widget.EditText' and n.get('text')==text for n in nodes()):return
  time.sleep(.3)
 raise AssertionError('Cursor insertion result did not appear: '+ascii(text))
def shot(name):
 shell('screencap -p /data/local/tmp/insert-screen.png');d.pull('/data/local/tmp/insert-screen.png',str(ev/name))
original_enabled=shell('settings get secure enabled_input_methods').strip();original_ime=shell('settings get secure default_input_method').strip();original_locales=shell('cmd locale get-app-locales '+pkg)
logfile=(root.parent/'cursor-insertion-device.log').open('w',encoding='utf-8');process=None
try:
 shell('cmd locale set-app-locales '+pkg+' --locales zh-CN')
 shell('settings put secure show_ime_with_hard_keyboard 1')
 shell('ime set com.android.inputmethod.latin/.LatinIME');shell('ime enable '+ime);shell('ime set '+ime)
 shell('am start -W -n '+pkg+'/com.osfans.trime.ui.main.MainActivity')
 shell('run-as '+pkg+' rm -f files/cursor-insertion-stage files/cursor-insertion-continue files/cursor-insertion-result.json')
 process=subprocess.Popen([sys.executable,str(root/'script/device.py'),'--port',str(port),'--timeout','900','shell','am instrument -w -e insert true '+testpkg+'/com.osfans.trime.ai.AiIntegrationInstrumentation'],stdout=logfile,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
 reply='好的，我会提前发提纲。'
 for case,prepare,action in [('middle','INSERT_PREPARE_MIDDLE','INSERT_REPLY_MIDDLE'),('cancel','INSERT_PREPARE_MOVE_CANCEL','INSERT_MOVE_CANCEL'),('accept','INSERT_PREPARE_MOVE_ACCEPT','INSERT_MOVE_ACCEPT')]:
  wait_stage(prepare);find(cls='android.widget.EditText')
  editor('前缀后缀',2,2,focus=True,clipboard='cursor-insertion-sentinel')
  time.sleep(3);signal();wait_stage(action)
  button=find(reply);assert button.get('enabled')=='true'
  if case=='middle':shot('screenshot-allowed-reply-chooser.png')
  if case!='middle':editor(start=4,end=4)
  tap(button)
  if case=='middle':
   assert_text('前缀'+reply+'后缀');shot('cursor-reply-middle.png')
   assert 'cursor-insertion-sentinel' in editor(read_clip=True),'Successful insertion changed clipboard'
   print('PASS reply page -> return -> insert in middle; clipboard unchanged',flush=True)
  else:
   find('插入 AI 文字')
   assert '前缀后缀' in editor(),'Cursor change caused an unapproved insertion'
   if case=='cancel':
    tap(find('取消'));assert_text('前缀后缀');print('PASS changed cursor -> cancel -> original text preserved',flush=True)
   else:
    tap(find('插入当前光标'));assert_text('前缀后缀'+reply);shot('cursor-reply-current.png');print('PASS changed cursor -> confirm -> insert at new cursor',flush=True)
  signal()
 wait_stage('INSERT_PREPARE_HIDDEN');find(cls='android.widget.EditText')
 editor('前缀后缀',2,2,focus=True,hide_keyboard=True,clipboard='cursor-insertion-sentinel')
 time.sleep(2);signal();wait_stage('INSERT_REPLY_HIDDEN')
 tap(find(reply));assert_text('前缀'+reply+'后缀')
 assert 'cursor-insertion-sentinel' in editor(read_clip=True),'Hidden-keyboard insertion changed clipboard'
 print('PASS hidden keyboard -> return -> automatic insertion at original cursor',flush=True)
 signal()
 wait_stage('INSERT_PREPARE_DIRECT_PAGE');find(cls='android.widget.EditText')
 editor('前缀后缀',2,2,focus=True,hide_keyboard=True,clipboard='cursor-insertion-sentinel')
 time.sleep(2);signal();wait_stage('INSERT_DIRECT_PAGE')
 assert_text('前缀'+reply+'后缀')
 assert 'cursor-insertion-sentinel' in editor(read_clip=True),'Direct-page insertion changed clipboard'
 print('PASS direct result page -> first reply inserted without chooser or tap',flush=True)
 signal()
 wait_stage('INSERT_PREPARE_REVIEW');find(cls='android.widget.EditText');editor('甲原文乙',1,3,focus=True);time.sleep(2);signal()
 wait_stage('INSERT_REVIEW_CONFIRM');find('确认插入')
 response=shell('am broadcast -a '+pkg+'.test.SET_REVIEW_TEXT -p '+pkg+' --es value '+shlex.quote('修改后文本'))
 assert 'review text updated' in response,'Review test editor did not update'
 tap(find('确认插入'));assert_text('甲修改后文本乙');shot('cursor-review-replace.png');signal()
 print('PASS edited AI draft -> original selected range replaced; surroundings preserved',flush=True)
 process.wait(timeout=45);logfile.flush()
 result=json.loads(shell('run-as '+pkg+' cat files/cursor-insertion-result.json'))
 assert re.fullmatch(r'0\.1\.\d+-ai', result['version']) and len(result['checks'])==6 and all(row.startswith('PASS:') for row in result['checks'])
 assert result['checks'][-1] in (root.parent/'cursor-insertion-device.log').read_text(encoding='utf-8')
 (ev/'cursor-insertion-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
 print('PASS all six cross-app insertion checks',flush=True)
finally:
 if process is not None and process.poll() is None:
  shell('am force-stop '+pkg);process.wait(timeout=30)
 shell('am force-stop '+pkg)
 shell('settings put secure enabled_input_methods '+shlex.quote(original_enabled));shell('settings put secure default_input_method '+shlex.quote(original_ime))
 match=re.search(r'\[(.*?)\]',original_locales);shell('cmd locale set-app-locales '+pkg+(' --locales '+shlex.quote(match[1]) if match and match[1] else ''))
 logfile.close();d.close()
