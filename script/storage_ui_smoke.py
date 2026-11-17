"""Checks storage defaults and onboarding on a local emulator; never calls an AI API."""
from pathlib import Path
from adb_shell.adb_device import AdbDeviceTcp
import base64
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
    output = shell('uiautomator dump /data/local/tmp/storage-window.xml')
    if 'dumped to:' not in output:
        return []
    return list(ET.fromstring(shell('cat /data/local/tmp/storage-window.xml')).iter('node'))

def find(text):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        for n in nodes():
            if n.get('text') == text:
                return n
        time.sleep(.4)
    raise AssertionError('Missing UI text: ' + ascii(text))

def tap(n):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
    shell(f'input tap {(x1+x2)//2} {(y1+y2)//2}')

def shot(name):
    shell('screencap -p /data/local/tmp/storage-screen.png')
    d.pull('/data/local/tmp/storage-screen.png', str(evidence / name))

prefs_path = 'shared_prefs/' + pkg + '_preferences.xml'
def read_prefs():
    return ET.fromstring(shell('run-as ' + pkg + ' cat ' + prefs_path))

def write_prefs(tree):
    data = base64.b64encode(ET.tostring(tree, encoding='utf-8', xml_declaration=True)).decode()
    cmd = 'mkdir -p shared_prefs; echo ' + data + ' | base64 -d > ' + prefs_path
    shell('run-as ' + pkg + ' sh -c ' + shlex.quote(cmd))

def get_pref(name):
    tree = read_prefs()
    return next((n.text for n in tree if n.get('name') == name), None)

def start():
    shell('am start -n ' + pkg + '/com.osfans.trime.ui.main.MainActivity')
    time.sleep(2)

result = {'version': '0.1.6-ai', 'ai_api_called': False}
try:
    original_enabled = shell('settings get secure enabled_input_methods').strip()
    original_selected = shell('settings get secure default_input_method').strip()
    original_locales = shell('cmd locale get-app-locales ' + pkg)
    shell('cmd locale set-app-locales ' + pkg + ' --locales zh-CN')
    shell('pm grant ' + pkg + ' android.permission.POST_NOTIFICATIONS')
    shell('ime set com.android.inputmethod.latin/.LatinIME')
    shell('ime disable ' + ime)
    shell('am force-stop ' + pkg)
    original_prefs = read_prefs()
    fresh = ET.fromstring(ET.tostring(original_prefs))
    for n in list(fresh):
        if n.get('name') in ('profile_data_storage_mode', 'profile_external_rime_tree_uri'):
            fresh.remove(n)
    write_prefs(fresh)
    start()
    find('启用输入法')
    find('第 1 步')
    assert get_pref('profile_data_storage_mode') == 'APP_STORAGE'
    assert get_pref('profile_external_rime_tree_uri') is None
    assert not any(n.get('class') == 'android.widget.RadioButton' for n in nodes())
    shot('storage-setup-enable.png')
    tap(find('下一步'))
    find('选择输入法')
    find('第 2 步')
    shot('storage-setup-select.png')
    result['new_install'] = 'PASS: app storage automatically selected; no folder URI or storage selector; two numbered IME steps'
    print('PASS default and two-step onboarding', flush=True)

    shell('am force-stop ' + pkg)
    legacy = read_prefs()
    for n in list(legacy):
        if n.get('name') in ('profile_data_storage_mode', 'profile_external_rime_tree_uri'):
            legacy.remove(n)
    ET.SubElement(legacy, 'string', name='profile_data_storage_mode').text = 'EXTERNAL_SYNC'
    uri = 'content://com.android.externalstorage.documents/tree/primary%3Arime-storage-test'
    ET.SubElement(legacy, 'string', name='profile_external_rime_tree_uri').text = uri
    write_prefs(legacy)
    marker = '/sdcard/Android/data/' + pkg + '/files/rime/storage-migration-test.txt'
    shell('mkdir -p ' + marker.rsplit('/', 1)[0])
    assert 'Permission denied' not in shell('echo preserved-local-config > ' + marker)
    start()
    find('启用输入法')
    assert get_pref('profile_data_storage_mode') == 'APP_STORAGE'
    assert get_pref('profile_external_rime_tree_uri') == uri
    assert shell('cat ' + marker).strip() == 'preserved-local-config'
    result['upgrade'] = 'PASS: legacy external mode becomes APP_STORAGE; local config and legacy folder reference preserved; no grant needed'
    print('PASS legacy storage migration preserves local data', flush=True)
    tap(find('跳过'))
    tap(find('好的，谢谢'))
    find('配置')
    tap(find('配置'))
    find('使用应用专属存储空间')
    shot('storage-settings.png')
    tap(find('数据存储模式'))
    time.sleep(.5)
    current = nodes()
    assert not any(n.get('class') == 'android.widget.RadioButton' for n in current)
    assert not any(n.get('text') == '从外部目录同步' for n in current)
    assert any('/Android/data/' + pkg + '/files/rime' in n.get('text', '') for n in current)
    result['settings'] = 'PASS: storage mode and app-specific path displayed; tapping does not open chooser'
    print('PASS read-only storage settings', flush=True)
    shell('rm ' + marker)
finally:
    shell('am force-stop ' + pkg)
    if 'original_prefs' in locals():
        write_prefs(original_prefs)
    if 'original_enabled' in locals():
        shell('settings put secure enabled_input_methods ' + shlex.quote(original_enabled))
    if 'original_selected' in locals():
        shell('settings put secure default_input_method ' + shlex.quote(original_selected))
    if 'original_locales' in locals():
        match = re.search(r'\[(.*?)\]', original_locales)
        shell('cmd locale set-app-locales ' + pkg + (' --locales ' + shlex.quote(match[1]) if match and match[1] else ''))
    d.close()
(evidence / 'storage-ui-result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
