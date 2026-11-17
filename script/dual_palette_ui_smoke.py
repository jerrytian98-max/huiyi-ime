"""Check both built-in palettes through settings and a synthetic editor. No AI calls."""
import json
import re
import time
from PIL import Image
import t9_ui_smoke as ui


def find_any(labels, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for node in ui.nodes():
            if node.get('text') in labels or node.get('content-desc') in labels:
                return node
        time.sleep(.3)
    raise AssertionError('Missing item ' + str(labels))


def tap_node(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    ui.tap((x1 + x2) // 2, (y1 + y2) // 2)
    time.sleep(.8)


def home():
    ui.shell('am start -W -f 0x00020000 -n ' + ui.pkg + '/com.osfans.trime.ui.main.MainActivity')
    time.sleep(2)
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        nodes = ui.nodes()
        texts = {node.get('text') for node in nodes}
        if texts.intersection(('General', '常规')):
            return
        if any(node.get('package') == ui.pkg for node in nodes) and texts and not any(text and text.startswith(('Huiyi Keyboard', '会意输入法')) for text in texts):
            ui.shell('input keyevent BACK')
        time.sleep(1)
    raise AssertionError('Settings home did not become visible')


def palette_dialog():
    home()
    tap_node(find_any(('Keyboard Style', '键盘样式')))
    tap_node(find_any(('Colors', '配色')))
    current = ui.nodes()
    choices = [node for node in current if node.get('class', '').endswith('CheckedTextView')]
    assert [node.get('text') for node in choices] == ['浅色', '深色']
    return choices


def palette(label, expected=None):
    choices = palette_dialog()
    if expected is not None:
        assert next(node for node in choices if node.get('text') == expected).get('checked') == 'true'
    ui.picture('dual-palette-choice-' + label + '-from-' + (expected or 'current') + '.png')
    tap_node(next(node for node in choices if node.get('text') == label))


def layout(t9):
    home()
    tap_node(find_any(('Virtual Keyboard', '虚拟键盘')))
    tap_node(find_any(('Chinese keyboard layout', '中文键盘布局')))
    options = ('Pinyin keypad (9 keys)', '九宫格（9键）') if t9 else ('Full keyboard (26 keys)', '全键盘（26键）')
    tap_node(find_any(options))
    find_any(options)


result = {'version': '0.1.17-ai', 'api': int(ui.shell('getprop ro.build.version.sdk')), 'network_requests': 0, 'checks': []}
try:
    assert ui.shell('getprop sys.boot_completed').strip() == '1'
    ui.shell('am force-stop ' + ui.pkg)
    ui.shell('ime enable ' + ui.ime)
    ui.shell('ime set ' + ui.ime)
    home()
    for index, (label, slug) in enumerate((('浅色', 'light'), ('深色', 'dark'))):
        palette(label)
        result['checks'].append(label + ' picker offers exactly two palettes and selects successfully')
        layout(False)
        ui.chat()
        ui.editor('', 33)
        ui.type_keys(ui.qwerty_hello)
        ui.expect('hello')
        ui.picture('dual-' + slug + '-letters.png')
        result['checks'].append(slug + ' QWERTY email editor inserts hello')
        ui.editor('')
        ui.tap(220, 2176)
        time.sleep(1)
        ui.picture('dual-' + slug + '-symbols.png')
        ui.type_keys([(64, 1705), (170, 1705)])
        ui.expect('12')
        result['checks'].append(slug + ' symbol page inserts 12')
        ui.editor('', 2)
        ui.type_keys(ui.number_123)
        ui.expect('123')
        ui.picture('dual-' + slug + '-numbers.png')
        result['checks'].append(slug + ' numeric editor inserts 123')
        layout(True)
        ui.chat()
        picture = 'dual-' + slug + '-t9.png'
        ui.picture(picture)
        # Pixel 6 fixture: the full English row has a gap at x=117; the T9 cell spans it.
        with Image.open(ui.evidence / picture) as image:
            english_page = image.getpixel((30, 1670)) != image.getpixel((117, 1670))
        if english_page:
            ui.tap(55, 2176)
            time.sleep(1)
            ui.picture(picture)
        ui.chinese('dual-' + slug + '-t9')
        result['checks'].append(slug + ' T9 candidates remain readable and insert 你好')
        print('PASS ' + slug + ' letters, symbols, numbers and T9', flush=True)
    ui.shell('am force-stop ' + ui.pkg)
    ui.shell('ime enable ' + ui.ime)
    ui.shell('ime set ' + ui.ime)
    palette('深色', expected='深色')
    result['checks'].append('Dark palette survives a full application process restart')
    palette('浅色', expected='深色')
    layout(False)
    (ui.evidence / 'dual-palette-ui-result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print('PASS palette persistence and restored light/QWERTY', flush=True)
finally:
    ui.d.close()
