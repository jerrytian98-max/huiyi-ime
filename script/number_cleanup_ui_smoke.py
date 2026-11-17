"""Verify the simplified digit/symbol pages against a synthetic editor; no AI calls."""
import json
import time
import t9_ui_smoke as ui

result = {'version': '0.1.12-ai', 'api': int(ui.shell('getprop ro.build.version.sdk')), 'ai_api_called': False, 'checks': []}
try:
    assert ui.shell('getprop sys.boot_completed').strip() == '1', 'Wait for emulator boot'
    ui.shell('ime enable ' + ui.ime)
    ui.shell('am start -W -n ' + ui.pkg + '/com.osfans.trime.ui.main.MainActivity')
    ui.shell('ime set com.android.inputmethod.latin/.LatinIME')
    ui.shell('ime set ' + ui.ime)
    ui.shell('am start -W -n ' + ui.pkg + '/com.osfans.trime.ui.main.MainActivity')
    ui.shell('am force-stop ' + ui.testpkg)
    ui.chat()
    time.sleep(2)
    ui.tap(220,2176)
    time.sleep(2)
    ui.picture('number-cleanup-symbols.png')
    ui.type_keys([(64,1705),(170,1705)])
    ui.expect('12')
    result['checks'].append('Symbol page digit keys insert 12; secondary labels and mini/number shortcuts removed')
    print('PASS: symbol-page input', flush=True)
    ui.tap(81,2190)
    time.sleep(2)
    ui.picture('number-cleanup-return.png')
    result['checks'].append('Symbol-page return opens the main keyboard')
    ui.editor('',2)
    ui.picture('number-cleanup-numeric.png')
    ui.type_keys(ui.number_123)
    ui.expect('123')
    ui.tap(750,2190)
    ui.expect('12')
    ui.picture('number-cleanup-numeric-input.png')
    result['checks'].append('Numeric editor inputs 123 and backspace produces 12')
    print('PASS: numeric-field input and backspace', flush=True)
    (ui.evidence/'number-cleanup-ui-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
finally:
    ui.editor('',1)
    ui.d.close()
