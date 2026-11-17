"""UI checks against TestChatActivity and the local mock provider only."""
from adb_shell.adb_device import AdbDeviceTcp
from pathlib import Path
import re
import time
import xml.etree.ElementTree as E
import json
import os
import shlex

root = Path(__file__).resolve().parents[1]
evidence = root / 'build/test-evidence'
evidence.mkdir(parents=True, exist_ok=True)
d = AdbDeviceTcp('127.0.0.1', 5557, default_transport_timeout_s=20)
d.connect(auth_timeout_s=5)

def shell(command):
    return d.shell(command, read_timeout_s=20, transport_timeout_s=20, timeout_s=25)

def nodes():
    output = shell('uiautomator dump /data/local/tmp/rime-ai-window.xml')
    if 'dumped to:' not in output:
        return []  # Android may be between activities or animating the IME.
    d.pull('/data/local/tmp/rime-ai-window.xml', str(evidence / 'window.xml'))
    return list(E.parse(evidence / 'window.xml').iter('node'))

def find(text=None, cls=None, timeout=60):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        for n in nodes():
            if (text is None or n.get('text') == text) and (cls is None or n.get('class') == cls):
                return n
        time.sleep(.3)
    raise AssertionError('UI element not found: ' + ascii(text or cls))

def tap_node(n):
    x1,y1,x2,y2 = map(int,re.findall(r'\d+',n.get('bounds')))
    shell(f'input tap {(x1+x2)//2} {(y1+y2)//2}')

def tap(text): tap_node(find(text))

def picture(name):
    shell('screencap -p /data/local/tmp/rime-ai-screen.png')
    d.pull('/data/local/tmp/rime-ai-screen.png',str(evidence / (name+'.png')))

def editor_text(): return find(cls='android.widget.EditText').get('text')

# Wait until the instrumented process has configured the mock TLS provider.
status_file = os.environ.get('RIME_AI_STATUS_FILE', '/data/local/tmp/rime-ai-interactive.txt')
for _ in range(45):
    if status_file == 'ready': break  # For a fixture already confirmed on screen.
    status_path = Path(status_file[5:]) if status_file.startswith('host:') else None
    status = (status_path.read_text(encoding='utf-8', errors='replace') if status_path.exists() else '') if status_path else shell('cat ' + status_file)
    if 'INTERACTIVE_READY' in status: break
    time.sleep(.5)
else: raise AssertionError('Test instrumentation did not become ready')

shell('settings put secure show_ime_with_hard_keyboard 1')
ime = 'com.rimeai.keyboard.debug/com.osfans.trime.ime.core.TrimeInputMethodService'
if shell('settings get secure default_input_method').strip() != ime:
    shell('ime enable '+ime)
    shell('ime set '+ime)
time.sleep(8.0)  # Allow cold IME deployment/rendering on a loaded emulator.
tap_node(find(cls='android.widget.EditText'))
time.sleep(1.0)
tap('填入测试文字')
time.sleep(1.0)
shell('input tap 420 1585')  # Pixel 6 fixture; selection toolbar owns the accessibility root.
find('插入整理稿')
picture('polish-preview')
tap('插入整理稿')
assert editor_text() == '明天下午三点开会。', ascii(editor_text())
print('PASS selected-text cleanup -> preview -> replace',flush=True)

# Speech uses the test-only RecognitionService, never real microphone audio.
shell('pm grant com.rimeai.keyboard.debug android.permission.RECORD_AUDIO')
shell('pm grant com.rimeai.keyboard.debug.test android.permission.RECORD_AUDIO')
shell('settings put secure voice_recognition_service com.rimeai.keyboard.debug.test/com.osfans.trime.ai.TestRecognitionService')
shell('input tap 210 1585')
find('插入整理稿')
picture('voice-preview')
tap('插入原文')
assert '嗯明天下午三点那个开会' in editor_text(), ascii(editor_text())
print('PASS speech partial/final callbacks -> AI preview -> original transcript',flush=True)

# A normal Activity editor can use the keyboard without recursively launching IME AI actions.
def edit_review(value):
    tap('编辑后插入')
    find('确认插入')
    tap_node(find(cls='android.widget.EditText'))
    output = shell('am broadcast --receiver-foreground -p com.rimeai.keyboard.debug -a com.rimeai.keyboard.debug.test.SET_REVIEW_TEXT --es value '+shlex.quote(value))
    assert 'review text updated' in output, output
    assert editor_text() == value, ascii(editor_text())
    # Wait for the IME/insets animation before locating the fixed action row.
    # A stale pre-animation coordinate could hit a keyboard character instead.
    previous = None
    stable = 0
    for _ in range(12):
        bounds = find('AI 重新整理').get('bounds')
        stable = stable + 1 if bounds == previous else 0
        if stable >= 2: break
        previous = bounds
        time.sleep(.4)
    else: raise AssertionError('Review action row did not settle')

tap('填入测试文字')
time.sleep(.5)
shell('input tap 420 1585')
find('插入整理稿')
edit_review('corrected meeting at four')
tap('AI 重新整理')
find('整理完成，请核对并确认插入。')
assert editor_text() == 'Corrected meeting at four.'
tap('确认插入')
find('填入测试文字')
assert editor_text() == 'Corrected meeting at four.', ascii(editor_text())
print('PASS edit draft -> repolish edited content -> replace original selection once',flush=True)

tap('填入测试文字')
time.sleep(.5)
shell('input tap 420 1585')
find('插入整理稿')
edit_review('discard this edited draft')
tap('取消')
find('填入测试文字')
assert editor_text() == '嗯明天下午三点那个开会', ascii(editor_text())
print('PASS cancelling review preserves original input',flush=True)

shell('input tap 420 1585')
find('插入整理稿')
edit_review('Manual correction only.')
tap('确认插入')
find('填入测试文字')
assert editor_text() == 'Manual correction only.', ascii(editor_text())
print('PASS manually edited draft inserts without another AI request',flush=True)

shell('input tap 630 1585')
tap('START')
# Android may return from projection consent with the IME hidden. Reopen the
# same editor; pending replies are intentionally shown only with an active IME.
tap_node(find(cls='android.widget.EditText'))
suggestion='好的，明天下午三点见，我会提前发提纲。'
find(suggestion)
picture('reply-preview')
tap(suggestion)
assert suggestion in editor_text(), ascii(editor_text())
print('PASS keyboard -> Android consent -> screenshot -> reply -> insert',flush=True)

before_cancel_text = editor_text()
shell('input tap 630 1585')
tap('CANCEL')
time.sleep(.5)
assert editor_text() == before_cancel_text
assert not any(n.get('text')==suggestion and n.get('class')!='android.widget.EditText' for n in nodes())
print('PASS fresh permission on next capture; cancellation exposes no old reply',flush=True)
picture('keyboard-tested')
shell('run-as com.rimeai.keyboard.debug touch files/finish-ui-checks')
(evidence/'ui-result.json').write_text(json.dumps({'passed':['selection cleanup','synthetic speech UI','edit and repolish draft','cancel edited draft','manual draft insertion','single screenshot and suggested insertion','fresh consent and cancellation']},ensure_ascii=False,indent=2),encoding='utf-8')
d.close()
