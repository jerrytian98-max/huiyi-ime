"""Verify Huiyi product names in actual Android UI without calling any AI API."""
from pathlib import Path
from adb_shell.adb_device import AdbDeviceTcp
import json
import re
import shlex
import time
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
evidence = root / 'build/test-evidence'
evidence.mkdir(parents=True, exist_ok=True)
pkg = 'com.rimeai.keyboard.debug'
ime = pkg + '/com.osfans.trime.ime.core.TrimeInputMethodService'
d = AdbDeviceTcp('127.0.0.1', 5557, default_transport_timeout_s=30)
d.connect(auth_timeout_s=5)

def shell(cmd):
    return d.shell(cmd, read_timeout_s=30, transport_timeout_s=30, timeout_s=35)

def nodes():
    output = shell('uiautomator dump /data/local/tmp/huiyi-window.xml')
    if 'dumped to:' not in output:
        return []
    return list(ET.fromstring(shell('cat /data/local/tmp/huiyi-window.xml')).iter('node'))

def find(text=None, desc=None):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        for n in nodes():
            if n.get('package') == pkg and ((text is not None and n.get('text') == text) or (desc is not None and n.get('content-desc') in desc)):
                return n
        time.sleep(.4)
    raise AssertionError('Missing UI element: ' + ascii(text or desc))

def tap(n):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
    shell(f'input tap {(x1+x2)//2} {(y1+y2)//2}')

def picture(name):
    shell('screencap -p /data/local/tmp/huiyi-screen.png')
    d.pull('/data/local/tmp/huiyi-screen.png', str(evidence / name))

def start(locale):
    shell('cmd locale set-app-locales ' + pkg + ' --locales ' + locale)
    shell('am force-stop ' + pkg)
    shell('am start -W -n ' + pkg + '/com.osfans.trime.ui.main.MainActivity')

result = {'version': '0.1.7-ai', 'ai_api_called': False}
original_enabled = shell('settings get secure enabled_input_methods').strip()
original_selected = shell('settings get secure default_input_method').strip()
original_locales = shell('cmd locale get-app-locales ' + pkg)
try:
    shell('ime set com.android.inputmethod.latin/.LatinIME')
    shell('ime disable ' + ime)
    shell('pm grant ' + pkg + ' android.permission.POST_NOTIFICATIONS')
    start('zh-CN')
    find('在语言和输入设置中启用会意输入法')
    picture('huiyi-setup-enable.png')
    tap(find('下一步'))
    find('选择会意输入法作为默认输入法')
    picture('huiyi-setup-select.png')
    print('PASS simplified Chinese onboarding branding', flush=True)
    tap(find('跳过'))
    tap(find('好的，谢谢'))
    find('会意输入法（内测）')
    find('听懂话，看懂意')
    time.sleep(8)  # Let the initial deployment heads-up notification leave the toolbar.
    shell('cmd statusbar collapse')
    picture('huiyi-main.png')
    tap(find('AI 设置'))
    find('会意 AI 设置')
    assert 'AiSettingsActivity' in shell('dumpsys activity activities')
    result['simplified_ui'] = 'PASS: both setup steps, main title and slogan, AI settings menu and page'
    print('PASS main title, slogan and AI settings', flush=True)
    start('en-US')
    find('Enable Huiyi Keyboard in Language and input settings')
    picture('huiyi-setup-english.png')
    result['english_ui'] = 'PASS: Huiyi Keyboard in English onboarding'
    print('PASS English branding', flush=True)
    start('zh-TW')
    find('在語言和輸入設定中啟用會意輸入法')
    result['traditional_ui'] = 'PASS: localized Huiyi name in Traditional Chinese onboarding'
    print('PASS Traditional Chinese branding', flush=True)
    start('zh-CN')
    tap(find('启用输入法'))
    # System settings resolves service labels using the system locale (English on this AVD).
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        current = nodes()
        labels = [n.get('text', '') for n in current]
        if any(v in labels for v in ('Huiyi Keyboard (Internal Test)', '会意输入法（内测）', '會意輸入法（內測）')):
            break
        time.sleep(.5)
    else:
        raise AssertionError('System input-method settings did not display the Huiyi name')
    picture('huiyi-system-input-methods.png')
    result['system_ime_ui'] = 'PASS: installed input method uses Huiyi brand in Android settings'
    print('PASS Android input-method label', flush=True)
finally:
    shell('am force-stop ' + pkg)
    shell('settings put secure enabled_input_methods ' + shlex.quote(original_enabled))
    shell('settings put secure default_input_method ' + shlex.quote(original_selected))
    match = re.search(r'\[(.*?)\]', original_locales)
    shell('cmd locale set-app-locales ' + pkg + (' --locales ' + shlex.quote(match[1]) if match and match[1] else ''))
    d.close()
(evidence / 'huiyi-brand-ui-result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
