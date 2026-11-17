"""End-to-end quick screenshot checks on the local synthetic Android emulator only."""
from pathlib import Path
from adb_shell.adb_device import AdbDeviceTcp
import base64
import json
import os
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as E

root=Path(__file__).resolve().parents[1]
evidence=root/'build/test-evidence'; evidence.mkdir(parents=True,exist_ok=True)
pkg='com.rimeai.keyboard.debug'
ime=pkg+'/com.osfans.trime.ime.core.TrimeInputMethodService'
a11y=pkg+'/com.osfans.trime.ai.AiAccessibilityCaptureService'
d=AdbDeviceTcp('127.0.0.1',5557,default_transport_timeout_s=30); d.connect(auth_timeout_s=5)

def shell(cmd): return d.shell(cmd,read_timeout_s=35,transport_timeout_s=35,timeout_s=40)
def nodes():
    if 'dumped to:' not in shell('uiautomator dump /data/local/tmp/quick-window.xml'): return []
    try:
        return list(E.fromstring(shell('cat /data/local/tmp/quick-window.xml')).iter('node'))
    except E.ParseError:
        return []
def find(text=None,desc=None,cls=None,timeout=60):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        for n in nodes():
            if (text is None or n.get('text')==text) and (desc is None or n.get('content-desc')==desc) and (cls is None or n.get('class')==cls): return n
        time.sleep(.4)
    raise AssertionError('UI missing: '+ascii(text or desc or cls))
def tap(n,long=False):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds'))); x=(x1+x2)//2; y=(y1+y2)//2
    shell(f'input swipe {x} {y} {x} {y} 700' if long else f'input tap {x} {y}')
def look_button():
    tap(find(cls='android.widget.EditText'))
    time.sleep(2)
    # Some Android builds omit the IME surface from uiautomator's hierarchy.
    for node in nodes():
        if node.get('content-desc') == 'AI回复': return node
    assert '1080x2400' in shell('wm size').replace(' ', ''), 'Expected Pixel 6 test fixture'
    return E.Element('node', bounds='[550,1550][740,1630]')
def picture(name):
    shell('screencap -p /data/local/tmp/quick-screen.png'); d.pull('/data/local/tmp/quick-screen.png',str(evidence/name))
def wait_stage(stage):
    end=time.monotonic()+180
    while time.monotonic()<end:
        value=shell('run-as '+pkg+' cat files/quick-capture-stage').strip()
        if value==stage: return
        if process.poll() is not None: raise AssertionError('Instrumentation ended before '+stage)
        time.sleep(.4)
    raise AssertionError('Stage timed out: '+stage)
def signal(): shell('run-as '+pkg+' touch files/quick-capture-continue')
def set_a11y(value):
    shell('settings put secure enabled_accessibility_services '+shlex.quote(value))
    shell('settings put secure accessibility_enabled '+('1' if value else '0'))

def edit(value):
    shell('am broadcast -a '+pkg+'.test.SET_EDITOR -p '+pkg+'.test --es value '+shlex.quote(value)+' --ei start 0 --ei end 0 --ez focus true')
    time.sleep(1)

def reply(round_number):
    return f'第{round_number}组：好的，明天下午三点见，我会提前发提纲。'

original_enabled=shell('settings get secure enabled_input_methods').strip()
original_ime=shell('settings get secure default_input_method').strip()
original_services=shell('settings get secure enabled_accessibility_services').strip()
original_a11y_enabled=shell('settings get secure accessibility_enabled').strip()
original_locales=shell('cmd locale get-app-locales '+pkg)
backups={}
for name in ('ai_capture_options.xml','ai_assistant_config.xml'):
    value=shell('run-as '+pkg+' cat shared_prefs/'+name)
    backups[name]=None if 'No such file' in value else value
process=None
result={'version':'0.1.15-ai','external_ai_calls':0,'system_grant':'Test harness enables service on emulator; phone users grant manually'}
logfile=(root.parent/'quick-capture-device.log').open('w',encoding='utf-8')
try:
    shell('cmd locale set-app-locales '+pkg+' --locales zh-CN')
    shell('settings put secure show_ime_with_hard_keyboard 1')
    shell('ime set com.android.inputmethod.latin/.LatinIME')
    shell('ime enable '+ime); shell('ime set '+ime)
    shell('am start -W -n '+pkg+'/com.osfans.trime.ui.main.MainActivity')
    time.sleep(3)
    shell('am start -n '+pkg+'.test/com.osfans.trime.ai.TestChatActivity')
    time.sleep(5)
    tap(look_button(),long=True)
    toggle=find('快捷截屏（免重复确认）')
    tap(toggle); find('开启快捷截屏')
    assert any('发送到你已配置的 AI 服务' in n.get('text','') for n in nodes())
    tap(find('取消'))
    assert find('快捷截屏（免重复确认）').get('checked')=='false'
    tap(find('快捷截屏（免重复确认）')); tap(find('同意并去授权'))
    result['consent_ui']='PASS: explicit upload disclosure; cancel preserves disabled mode; agree opens system Accessibility settings'
    print('PASS quick-mode consent and cancel UI',flush=True)
    baseline_services='' if original_services in ('null','') else original_services
    services=':'.join([s for s in baseline_services.split(':') if s and s!=a11y]+[a11y])
    set_a11y(baseline_services)
    shell('run-as '+pkg+' rm -f files/quick-capture-stage files/quick-capture-continue files/quick-capture-result.json')
    process=subprocess.Popen([sys.executable,str(root/'script/device.py'),'--timeout','600','shell','am instrument -w -e quick true '+pkg+'.test/com.osfans.trime.ai.AiIntegrationInstrumentation'],stdout=logfile,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    wait_stage('QUICK_CONNECT_READY')
    set_a11y(services)
    wait_stage('QUICK_KEYBOARD_READY')
    screen=look_button(); time.sleep(1.5); tap(screen)
    time.sleep(3)
    find(reply(1)); picture('capture-regeneration-initial.png')
    tap(find('再次生成')); find(reply(2))
    tap(find('再次生成')); find(reply(3)); picture('capture-regeneration-replies.png')
    tap(find('关闭')); edit('fresh screenshot after close')
    tap(look_button()); time.sleep(3); find(reply(4))
    shell('input keyevent BACK'); edit('fresh screenshot after back')
    tap(look_button()); time.sleep(3); find(reply(5))
    suggestion=reply(5)
    tap(find(suggestion))
    deadline=time.monotonic()+20
    while time.monotonic()<deadline:
        inserted=find(cls='android.widget.EditText').get('text','')
        if inserted.count(suggestion)==1 and inserted.replace(suggestion,'',1)=='fresh screenshot after back': break
        time.sleep(.3)
    else:
        raise AssertionError('Reply was not inserted exactly once while preserving the draft')
    picture('capture-regeneration-inserted.png')
    tap(look_button()); time.sleep(3); find(reply(6)); shell('input tap 60 180'); time.sleep(1)
    result['keyboard_quick_capture']='PASS: regenerate twice, Close -> fresh screenshot, Back -> fresh screenshot, insert -> fresh screenshot, outside dismissal clears session'
    print('PASS regenerate, exit and recapture through actual keyboard UI',flush=True)
    signal()
    wait_stage('QUICK_REPLY_ACTIVITY_READY')
    find(reply(7)); tap(find('再次生成')); find(reply(8))
    tap(find('关闭'))
    shell('am start -W -n '+pkg+'.test/com.osfans.trime.ai.TestChatActivity')
    time.sleep(1)
    result['reply_activity']='PASS: actual reply Activity regenerates and clears on Return'
    signal()
    wait_stage('QUICK_REVOKE_READY')
    set_a11y(baseline_services); time.sleep(2); signal()
    wait_stage('QUICK_FALLBACK_READY')
    tap(look_button())
    # The ordinary flow must visibly ask for screen sharing after authorization is revoked.
    cancel=None
    for _ in range(25):
        current=nodes()
        for n in current:
            if n.get('text') in ('Cancel','CANCEL','取消') and n.get('package')=='com.android.systemui': cancel=n; break
        if cancel is not None: break
        time.sleep(.3)
    assert cancel is not None,'No MediaProjection consent after permission revocation'
    picture('quick-capture-revoked-fallback.png'); tap(cancel); time.sleep(1); signal()
    result['fallback_ui']='PASS: revoking accessibility permission restores ordinary screen-sharing consent; cancel handled'
    print('PASS revoked permission -> ordinary consent -> cancel',flush=True)
    wait_stage('STANDARD_REGENERATION_READY')
    tap(look_button())
    consent=None
    for _ in range(25):
        for n in nodes():
            if n.get('text') in ('Start now','START NOW','立即开始','现在开始') and n.get('package')=='com.android.systemui':
                consent=n
                break
        if consent is not None: break
        time.sleep(.3)
    assert consent is not None,'Ordinary capture consent button missing'
    tap(consent)
    time.sleep(3)
    find(reply(9)); tap(find('再次生成')); find(reply(10))
    picture('capture-regeneration-standard.png'); tap(find('关闭')); signal()
    result['ordinary_capture']='PASS: real MediaProjection screenshot -> regenerate from same image -> exit'
    process.wait(timeout=45); logfile.flush()
    log=(root.parent/'quick-capture-device.log').read_text(encoding='utf-8')
    device=json.loads(shell('run-as '+pkg+' cat files/quick-capture-result.json'))
    # With a result Bundle.stream, am instrument pretty-prints the stream instead of a code line.
    assert device.get('version')=='0.1.15-ai' and len(device.get('checks',[]))==12
    assert all(row.startswith('PASS:') for row in device['checks'])
    assert device['checks'][-1] in log, 'Device checks did not reach their final result'
    result['android_checks']=device['checks']
    records=[]
    for line in (root.parent/'capture-regeneration-mock.log').read_text(encoding='utf-8').splitlines():
        if line.startswith('{'):
            row=json.loads(line)
            if row.get('model')=='mock-vision-regenerate' and 'image_sha256' in row: records.append(row)
    assert [r['round'] for r in records]==list(range(1,11)), records
    hashes=[r['image_sha256'] for r in records]
    assert hashes[0]==hashes[1]==hashes[2], 'Regeneration changed the screenshot'
    assert len(set([hashes[0],*hashes[3:6]]))==4, 'Exiting did not capture fresh screens'
    assert hashes[6]==hashes[7], 'Activity regeneration changed the screenshot'
    assert hashes[8]==hashes[9], 'Ordinary capture regeneration changed the screenshot'
    assert [r['retry_prompt'] for r in records]==[False,True,True,False,False,False,False,True,False,True]
    result['image_requests']=records
    result['api']=int(shell('getprop ro.build.version.sdk').strip())
    (evidence/'capture-regeneration-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print('PASS all Android screenshot, opt-in, cancellation, revoke and fallback checks',flush=True)
finally:
    if process is not None and process.poll() is None:
        shell('am force-stop '+pkg)
        process.wait(timeout=30)
    shell('am force-stop '+pkg)
    for name,raw in backups.items():
        path='shared_prefs/'+name
        if raw is None:
            shell('run-as '+pkg+' rm -f '+path)
        else:
            data=base64.b64encode(raw.encode('utf-8')).decode()
            shell('run-as '+pkg+' sh -c '+shlex.quote('echo '+data+' | base64 -d > '+path))
    shell('settings put secure enabled_accessibility_services '+shlex.quote(original_services if original_services!='null' else ''))
    shell('settings put secure accessibility_enabled '+original_a11y_enabled)
    shell('settings put secure enabled_input_methods '+shlex.quote(original_enabled))
    shell('settings put secure default_input_method '+shlex.quote(original_ime))
    match=re.search(r'\[(.*?)\]',original_locales)
    shell('cmd locale set-app-locales '+pkg+(' --locales '+shlex.quote(match[1]) if match and match[1] else ''))
    logfile.close(); d.close()
