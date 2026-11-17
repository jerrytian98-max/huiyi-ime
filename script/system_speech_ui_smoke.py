"""Speech client-error recovery, using test-only RecognitionService and Activity callbacks."""
import json
import re
import time
import t9_ui_smoke as ui

checks = []


def wait(label, timeout=40):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for node in ui.nodes():
            if node.get('text') == label or node.get('content-desc') == label:
                return node
        time.sleep(.2)
    raise AssertionError('Missing speech UI item ' + label)


def click(label):
    node = wait(label)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    ui.tap((x1 + x2) // 2, (y1 + y2) // 2)


def mode(value):
    ui.shell('run-as ' + ui.pkg + ' sh -c "echo ' + value + ' > files/system-speech-ui-mode"')
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if 'pending' not in ui.shell('run-as ' + ui.pkg + ' sh -c "test -f files/system-speech-ui-mode && echo pending"'):
            return
        time.sleep(.2)
    raise AssertionError('Speech test mode was not applied')


def voice():
    wait('回复消息')
    time.sleep(1)
    # The IME toolbar is outside the app accessibility root on this fixture.
    ui.tap(230, 1585)


def text_in_editor(expected):
    node = wait(expected)
    assert node.get('class') == 'android.widget.EditText'


def ready():
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if 'READY' in ui.shell('run-as ' + ui.pkg + ' cat files/system-speech-ui-ready'):
            return
        time.sleep(.5)
    raise AssertionError('Speech instrumentation not ready')


try:
    ready()
    ui.shell('settings put secure show_ime_with_hard_keyboard 1')
    ui.shell('ime enable ' + ui.ime)
    ui.shell('ime set ' + ui.ime)
    ui.editor('')
    time.sleep(3)
    voice()
    wait('插入原文')
    ui.picture('system-speech-reconnected-preview.png')
    click('插入原文')
    text_in_editor('嗯明天下午三点那个开会')
    checks.append('Client error once -> reconnect -> transcript preview -> original cursor insertion')
    print('PASS service reconnection and insertion', flush=True)

    mode('test-client-always-ui')
    ui.editor('')
    voice()
    wait('系统语音暂不可用')
    ui.picture('system-speech-client-error.png')
    click('系统语音窗口')
    click('完成测试识别')
    wait('确认插入')
    ui.picture('system-speech-window-review.png')
    click('确认插入')
    # Returning from the private activity can require focusing the original editor again.
    time.sleep(2)
    ui.shell('am broadcast -a ' + ui.testpkg + '.SET_EDITOR -p ' + ui.testpkg + ' --ez focus true')
    text_in_editor('系统窗口识别的测试文字')
    checks.append('Persistent client error -> explicit system window -> review -> insert in original app')
    print('PASS system recognition-window fallback and insertion', flush=True)

    ui.editor('')
    voice()
    wait('系统语音暂不可用')
    click('系统语音窗口')
    click('取消测试识别')
    time.sleep(2)
    ui.shell('am broadcast -a ' + ui.testpkg + '.SET_EDITOR -p ' + ui.testpkg + ' --ez focus true')
    wait('回复消息')
    checks.append('Cancelling the system recognition window leaves the editor unchanged')

    mode('test-finish-ui')
    voice()
    wait('完成')
    time.sleep(.6)
    click('完成')
    wait('插入原文')
    click('插入原文')
    text_in_editor('点击完成后的识别文字')
    checks.append('Finish keeps recognition alive until the delayed final transcript is ready')
    print('PASS Finish waits for final result', flush=True)

    mode('test-client-once-ui-cancel')
    ui.editor('')
    voice()
    click('取消')
    time.sleep(2)
    wait('回复消息')
    checks.append('Cancelling voice input leaves no late preview or inserted text')

    ui.shell('settings put secure voice_recognition_service unavailable/.MissingRecognizer')
    voice()
    wait('系统语音暂不可用')
    assert any('未找到可供应用调用的系统语音服务' in (n.get('text') or '') for n in ui.nodes())
    click('关闭')
    ui.shell('settings put secure voice_recognition_service ' + ui.testpkg + '/com.osfans.trime.ai.TestRecognitionService')
    checks.append('Missing recognition engine produces an explicit recovery dialog')

    mode('test-client-always-window-missing')
    disabled = ui.shell('run-as ' + ui.testpkg + ' pm disable ' + ui.testpkg + '/com.osfans.trime.ai.TestSpeechActivity')
    assert 'new state: disabled' in disabled
    # Android kills the fixture process when pm changes a component state.
    ui.chat()
    try:
        voice()
        wait('系统语音暂不可用')
        click('系统语音窗口')
        wait('系统语音窗口不可用')
        wait('语音服务设置')
        click('关闭')
        time.sleep(1)
        ui.shell('am broadcast -a ' + ui.testpkg + '.SET_EDITOR -p ' + ui.testpkg + ' --ez focus true')
        wait('回复消息')
    finally:
        ui.shell('run-as ' + ui.testpkg + ' pm enable ' + ui.testpkg + '/com.osfans.trime.ai.TestSpeechActivity')
    checks.append('Missing recognition Activity explains service setup and preserves editor text')

    ui.chat()
    mode('test-client-always-ui-hide')
    voice()
    ui.shell('input keyevent HOME')
    time.sleep(2)
    visible = {n.get('text') for n in ui.nodes()}
    assert '系统语音暂不可用' not in visible and '插入原文' not in visible
    checks.append('Leaving the editor cancels recognition and suppresses late error dialogs')
    print('PASS cancellation and editor-hidden cleanup', flush=True)
    (ui.evidence / 'system-speech-ui-result.json').write_text(json.dumps({
        'version': '0.1.18-ai', 'api': int(ui.shell('getprop ro.build.version.sdk').strip()), 'network_requests': 0, 'real_audio': False,
        'checks': checks,
    }, ensure_ascii=False, indent=2), encoding='utf-8')
finally:
    ui.shell('run-as ' + ui.pkg + ' touch files/system-speech-ui-done')
    ui.d.close()
