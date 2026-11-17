"""Android keyboard-layout smoke checks using a separate synthetic editor; no AI calls."""
from pathlib import Path
from adb_shell.adb_device import AdbDeviceTcp
import json
import os
import re
import shlex
import time
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
evidence = root / 'build/test-evidence'
evidence.mkdir(parents=True, exist_ok=True)
pkg = 'com.rimeai.keyboard.debug'
testpkg = pkg + '.test'
ime = pkg + '/com.osfans.trime.ime.core.TrimeInputMethodService'
d = AdbDeviceTcp('127.0.0.1', int(os.environ.get('HUIYI_EMULATOR_PORT', '5557')), default_transport_timeout_s=30)
d.connect(auth_timeout_s=5)


def shell(cmd):
    return d.shell(cmd, read_timeout_s=45, transport_timeout_s=45, timeout_s=50)


def nodes():
    response = shell('uiautomator dump /data/local/tmp/t9-window.xml')
    if 'dumped to:' not in response:
        return []
    try:
        return list(ET.fromstring(shell('cat /data/local/tmp/t9-window.xml')).iter('node'))
    except ET.ParseError:
        return []


def find(label):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        for n in nodes():
            if n.get('text') == label or n.get('content-desc') == label:
                return n
        time.sleep(.3)
    raise AssertionError('Missing UI element: ' + label)


def tap(x, y):
    shell(f'input tap {x} {y}')


def click(label):
    n = find(label)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
    time.sleep(1)
    tap((x1+x2)//2, (y1+y2)//2)
    time.sleep(1)


def picture(name):
    shell('screencap -p /data/local/tmp/t9-screen.png')
    d.pull('/data/local/tmp/t9-screen.png', str(evidence / name))


def keyboard_settings():
    shell('am start -W -n ' + pkg + '/com.osfans.trime.ui.main.MainActivity')
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        current = nodes()
        if any(n.get('text') == '中文键盘布局' for n in current):
            return
        for n in current:
            if n.get('text') == '虚拟键盘':
                x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
                tap((x1+x2)//2, (y1+y2)//2)
                break
        time.sleep(2)
    raise AssertionError('Keyboard settings did not open')


def layout(label):
    keyboard_settings()
    click('中文键盘布局')
    click(label)
    find(label)


def editor(value=None, input_type=None):
    cmd = 'am broadcast -a ' + testpkg + '.SET_EDITOR -p ' + testpkg
    if value is not None:
        cmd += ' --es value ' + shlex.quote(value) + ' --ei start 0 --ei end 0'
    if input_type is not None:
        cmd += ' --ei inputType ' + str(input_type)
    if value is not None or input_type is not None:
        cmd += ' --ez focus true'
    response = shell(cmd)
    if input_type is not None:
        # Changing EditText's input type restarts its InputConnection asynchronously.
        time.sleep(3)
    return response


def chat():
    shell('am start -W -n ' + testpkg + '/com.osfans.trime.ai.TestChatActivity')
    editor('', 1)
    tap(350, 660)
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if 'mInputShown=true' in shell('dumpsys input_method'):
            time.sleep(1)
            return
        time.sleep(.5)
    raise AssertionError('Input method did not become visible')


def type_keys(points):
    for point in points:
        tap(*point)
        # Let emulator input dispatch/rendering settle between individual shell injections.
        time.sleep(.5)


t9_nihao = [(672,1872),(142,1872),(142,1872),(408,1720),(672,1872)]
qwerty_hello = [(648,1872),(275,1720),(968,1872),(968,1872),(912,1720)]
number_123 = [(328,1705),(540,1705),(750,1705)]


def expect(value):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        response = editor()
        if 'data="' + value + '"' in response:
            return
        time.sleep(.3)
    raise AssertionError(response)


def chinese(name):
    type_keys(t9_nihao)
    picture(name + '-candidates.png')
    tap(106, 1585)
    time.sleep(.5)
    expect('你好')


if __name__ == '__main__':
    result = {'version': '0.1.11-ai', 'api': 35, 'ai_api_called': False, 'checks': []}
    try:
        boot_deadline = time.monotonic() + 120
        while shell('getprop sys.boot_completed').strip() != '1':
            assert time.monotonic() < boot_deadline, 'Emulator did not finish booting'
            time.sleep(1)
        shell('ime enable ' + ime)
        shell('ime set com.android.inputmethod.latin/.LatinIME')
        shell('ime set ' + ime)
        shell('am force-stop ' + testpkg)
        layout('全键盘（26键）')
        layout('九宫格（9键）')
        picture('t9-layout-settings.png')
        print('PASS: layout selected through settings', flush=True)
        chat()
        picture('t9-layout-keyboard.png')
        chinese('t9-nihao')
        picture('t9-chinese-inserted.png')
        result['checks'].append('Settings selects T9; onscreen 64426 -> 你好 -> editor')
        print('PASS: onscreen T9 candidates and insertion', flush=True)
        editor('')
        tap(142,2176)
        time.sleep(1)
        type_keys(qwerty_hello)
        expect('hello')
        picture('t9-english-hello.png')
        result['checks'].append('Chinese to English switches to QWERTY; onscreen hello')
        editor('')
        tap(90,2176)
        time.sleep(1)
        tap(938,1872)
        time.sleep(1)
        type_keys(number_123)
        expect('123')
        picture('t9-number-panel.png')
        tap(328,2190)
        time.sleep(1)
        editor('')
        chinese('t9-number-return')
        result['checks'].append('123 panel inputs digits; return restores Chinese T9')
        print('PASS: English and number-panel round trip', flush=True)
        editor('',129)
        time.sleep(1)
        type_keys(qwerty_hello)
        expect('hello')
        picture('t9-password.png')
        editor('',2)
        time.sleep(1)
        picture('t9-numeric-field.png')
        type_keys(number_123)
        expect('123')
        editor('',1)
        time.sleep(1)
        chinese('t9-normal-field-restored')
        result['checks'].append('Password uses QWERTY; numeric field uses numbers; normal field restores T9')
        print('PASS: password, numeric and normal editors', flush=True)
        shell('am force-stop ' + pkg)
        shell('ime set com.android.inputmethod.latin/.LatinIME')
        shell('ime set ' + ime)
        keyboard_settings()
        find('九宫格（9键）')
        picture('t9-restart-settings.png')
        chat()
        chinese('t9-restart')
        result['checks'].append('Process restart retains T9 preference and native schema')
        layout('全键盘（26键）')
        picture('t9-qwerty-settings.png')
        chat()
        type_keys([(648,1872),(116,1872),(754,2023),(593,1720),(700,1720)])
        picture('t9-qwerty-hanyu-candidates.png')
        tap(540,2176)
        time.sleep(.5)
        expect('汉语')
        editor('')
        picture('t9-qwerty-clean-keycaps.png')
        shell('input swipe 540 2176 540 2176 900')
        time.sleep(1)
        assert 'mInputShown=true' in shell('dumpsys input_method')
        result['checks'].append('Settings selects QWERTY; onscreen hanyu -> space -> 汉语; long space press does not hide IME')
        print('PASS: restart persistence, QWERTY input and removed hide long press', flush=True)
        (evidence / 't9-layout-ui-result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    finally:
        editor('',1)
        d.close()
