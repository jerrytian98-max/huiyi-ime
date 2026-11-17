"""Real microphone path using injected synthetic audio, Tencent ASR and the configured text model."""
import json
import os
from pathlib import Path
import re
import sys
import time
import wave
import grpc
import grpc_tools
from grpc_tools import protoc
import t9_ui_smoke as ui

root = Path(__file__).resolve().parents[1]
bindings = root / 'build/emulator-grpc'
bindings.mkdir(parents=True, exist_ok=True)
proto = root.parent / '.tools/sdk/emulator/lib'
assert protoc.main(['grpc_tools.protoc', '-I' + str(proto), '-I' + str(Path(grpc_tools.__file__).parent / '_proto'), '--python_out=' + str(bindings), '--grpc_python_out=' + str(bindings), str(proto / 'emulator_controller.proto')]) == 0
sys.path.insert(0, str(bindings))
import emulator_controller_pb2 as pb
import emulator_controller_pb2_grpc as rpc

settings = None
for discovery in (Path(os.environ['TEMP']) / 'avd/running').glob('pid_*.ini'):
    values = dict(line.split('=', 1) for line in discovery.read_text().splitlines() if '=' in line)
    if values.get('grpc.port') == '8554':
        settings = values
assert settings and settings.get('grpc.token'), 'Authenticated emulator endpoint not found'
metadata = [('authorization', 'Bearer ' + settings['grpc.token'])]
stub = rpc.EmulatorControllerStub(grpc.insecure_channel('127.0.0.1:8554'))
stub.setMicrophoneState(pb.MicrophoneState(realAudioEnabled=False), metadata=metadata, timeout=5)
checks = []
succeeded = False


def wait(text, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for node in ui.nodes():
            if node.get('text') == text:
                return node
        time.sleep(.3)
    raise AssertionError('Missing Tencent voice UI item ' + text)


def click(text):
    node = wait(text)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    ui.tap((x1 + x2) // 2, (y1 + y2) // 2)


def voice():
    time.sleep(1)
    ui.tap(230, 1585)
    wait('正在录音 · 云端转写')
    time.sleep(.5)


def inject(seconds=None):
    with wave.open(str(root / 'app/src/androidTest/assets/speech-zh.wav'), 'rb') as audio:
        assert (audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) == (1, 2, 16000)
        frames = audio.getnframes() if seconds is None else min(audio.getnframes(), int(seconds * 16000))
        mono = b'\x00' * 6400 + audio.readframes(frames) + b'\x00' * 12800
    pcm = b''.join(mono[index:index + 2] * 6 for index in range(0, len(mono), 2))

    def packets():
        fmt = pb.AudioFormat(samplingRate=48000, channels=pb.AudioFormat.Stereo, format=pb.AudioFormat.AUD_FMT_S16, mode=pb.AudioFormat.MODE_REAL_TIME)
        started = time.monotonic()
        for offset in range(0, len(pcm), 3840):
            delay = started + offset / 192000 - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            yield pb.AudioPacket(format=fmt, timestamp=int(time.time() * 1_000_000), audio=pcm[offset:offset + 3840])
    stub.injectAudio(packets(), metadata=metadata, timeout=45)


def released():
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if 'running' not in ui.shell('cmd appops get ' + ui.pkg + ' RECORD_AUDIO').lower():
            return
        time.sleep(.3)
    raise AssertionError('Microphone is still active')


try:
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        if 'READY' in ui.shell('run-as ' + ui.pkg + ' cat files/tencent-speech-ui-ready'):
            break
        time.sleep(.5)
    else:
        raise AssertionError('Tencent Android checks not ready')
    ui.shell('settings put secure show_ime_with_hard_keyboard 1')
    ui.shell('ime enable ' + ui.ime)
    ui.shell('ime set ' + ui.ime)
    wait('回复消息')
    time.sleep(6)
    voice()
    inject()
    click('完成')
    wait('插入整理稿', timeout=90)
    preview = ' '.join(node.get('text', '') for node in ui.nodes())
    assert '提纲' in preview and '预算' in preview and '1500' in preview
    ui.picture('tencent-speech-real-preview.png')
    click('插入整理稿')
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        editors = [node for node in ui.nodes() if node.get('class') == 'android.widget.EditText']
        if editors and '提纲' in editors[0].get('text', '') and '1500' in editors[0].get('text', ''):
            inserted = editors[0].get('text')
            break
        time.sleep(.3)
    else:
        raise AssertionError('Recognized and polished text was not inserted')
    released()
    checks.append('Injected synthetic PCM -> Android AudioRecord -> real Tencent general ASR -> real DeepSeek -> preview -> original cursor insertion')
    print('PASS real cloud voice and insertion', flush=True)

    voice()
    click('取消')
    released()
    assert wait(inserted).get('class') == 'android.widget.EditText'
    checks.append('Cancel releases the microphone and preserves existing editor text')

    ui.shell('run-as ' + ui.pkg + ' sh -c "echo 16k_zh_en > files/tencent-speech-ui-mode"')
    time.sleep(1)
    voice()
    inject(2)
    click('完成')
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        texts = {node.get('text', '') for node in ui.nodes()}
        if '语音转写未完成' in texts:
            assert any('4004' in text and '资源包' in text for text in texts)
            ui.picture('tencent-speech-quota-error.png')
            click('关闭')
            checks.append('Actual large-model quota error is explained and existing editor text is preserved')
            break
        if '插入整理稿' in texts:
            click('取消')
            checks.append('Large-model quota became available and the voice UI produced a real preview')
            break
        time.sleep(.3)
    else:
        raise AssertionError('Large-model request produced neither a result nor an actionable error')
    released()
    assert wait(inserted).get('class') == 'android.widget.EditText'
    print('PASS quota/result handling and original text preservation', flush=True)

    voice()
    ui.shell('input keyevent HOME')
    time.sleep(1)
    released()
    visible = {node.get('text') for node in ui.nodes()}
    assert '正在录音 · 云端转写' not in visible
    checks.append('Leaving the editor cancels recording and releases the microphone')
    print('PASS cancellation and editor-hidden cleanup', flush=True)
    succeeded = True
finally:
    (ui.evidence / 'tencent-speech-ui-result.json').write_text(json.dumps({
        'version': '0.1.19-ai', 'api': 35, 'source': 'Synthetic WAV injected into emulator microphone; host microphone disabled',
        'recognition_model_for_success_case': '16k_zh', 'text_provider': 'DeepSeek',
        'passed': succeeded, 'checks': checks,
    }, ensure_ascii=False, indent=2), encoding='utf-8')
    ui.shell('run-as ' + ui.pkg + ' sh -c "echo ' + ('PASS' if succeeded else 'FAIL') + ' > files/tencent-speech-ui-done"')
    ui.d.close()
