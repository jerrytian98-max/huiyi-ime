"""Actual space-key touch gestures with synthetic PCM and the real cloud transcription client."""
import json
import os
from pathlib import Path
import re
import sys
import socket
import time
import wave
import grpc
import grpc_tools
from grpc_tools import protoc
from adb_shell.adb_device import AdbDeviceTcp
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
assert settings and settings.get('grpc.token'), 'Authenticated emulator audio endpoint not found'
metadata = [('authorization', 'Bearer ' + settings['grpc.token'])]
stub = rpc.EmulatorControllerStub(grpc.insecure_channel('127.0.0.1:8554'))
stub.setMicrophoneState(pb.MicrophoneState(realAudioEnabled=False), metadata=metadata, timeout=5)
checks = []
pressed = False
point = (540, 2176)
succeeded = False


def record(message):
    checks.append(message)
    print('PASS ' + message, flush=True)
    save(False)


def save(passed):
    (ui.evidence / 'space-hold-ui-result.json').write_text(json.dumps({
        'version': '0.1.21-ai', 'api': 35, 'passed': passed, 'checks': checks,
        'audio_source': 'Synthetic WAV injected into emulator microphone; host microphone disabled',
        'provider': 'Real Tencent 16k_zh', 'text_ai_used': False,
    }, ensure_ascii=False, indent=2), encoding='utf-8')


def edit(value=None, start=None, end=None, input_type=None):
    import shlex
    command = 'am broadcast -a ' + ui.testpkg + '.SET_EDITOR -p ' + ui.testpkg
    if value is not None:
        command += ' --es value ' + shlex.quote(value)
    if start is not None:
        command += ' --ei start ' + str(start) + ' --ei end ' + str(end if end is not None else start)
    if input_type is not None:
        command += ' --ei inputType ' + str(input_type)
    if value is not None or start is not None or input_type is not None:
        command += ' --ez focus true'
    response = ui.shell(command)
    if input_type is not None:
        time.sleep(3)
    return response


def current():
    response = edit()
    match = re.search(r'data="(.*)"', response, re.S)
    if match:
        return match.group(1)
    for node in ui.nodes():
        if node.get('class') == 'android.widget.EditText':
            return node.get('text', '') if node.get('text') != '回复消息' else ''
    raise AssertionError('No editor result')


def expected(value, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if current() == value:
            return
        time.sleep(.3)
    raise AssertionError('Editor differs from expected value')


def microphone(active, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        running = 'running' in ui.shell('cmd appops get ' + ui.pkg + ' RECORD_AUDIO').lower()
        if running == active:
            return
        time.sleep(.2)
    raise AssertionError('Unexpected microphone activity')


def down():
    global pressed
    ui.shell('input touchscreen motionevent DOWN ' + str(point[0]) + ' ' + str(point[1]))
    pressed = True


def up():
    global pressed
    ui.shell('input touchscreen motionevent UP ' + str(point[0]) + ' ' + str(point[1]))
    pressed = False


def network_delay(value):
    with socket.create_connection(('127.0.0.1', 5562), timeout=5) as console:
        console.settimeout(5)

        def response():
            data = b''
            while not (data.endswith(b'OK\r\n') or b'KO:' in data):
                chunk = console.recv(4096)
                if not chunk:
                    break
                data += chunk
            return data

        response()
        console.sendall(('auth ' + (Path.home() / '.emulator_console_auth_token').read_text().strip() + '\n').encode())
        assert b'OK' in response()
        console.sendall(('network delay ' + value + '\n').encode())
        assert b'OK' in response()


def inject(seconds=None):
    with wave.open(str(root / 'app/src/androidTest/assets/speech-zh.wav'), 'rb') as audio:
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


def wait_transcript(prefix='', suffix='', timeout=70):
    if prefix == '九宫格':
        time.sleep(.5)
        ui.picture('space-hold-t9-after-release.png')
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = current()
        if value.startswith(prefix) and value.endswith(suffix) and '提纲' in value and '预算' in value and '1500' in value:
            texts = {node.get('text') for node in ui.nodes()}
            assert '整理结果' not in texts and '插入整理稿' not in texts
            return value
        time.sleep(.3)
    ui.picture('space-hold-transcript-failure.png')
    print('Editor at failure: ' + repr(current()), flush=True)
    print('Keyboard at failure: ' + repr([(n.get('text'), n.get('content-desc')) for n in ui.nodes() if n.get('text') or n.get('content-desc')]), flush=True)
    raise AssertionError('No direct transcript in the editor')


try:
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        if 'READY' in ui.shell('run-as ' + ui.pkg + ' cat files/space-hold-ready'):
            break
        time.sleep(.5)
    else:
        raise AssertionError('Space-key instrumentation not ready')
    ui.shell('settings put secure show_ime_with_hard_keyboard 1')
    ui.shell('ime enable ' + ui.ime)
    ui.shell('ime set ' + ui.ime)
    edit('', 0, input_type=33)
    time.sleep(5)
    if '--extended-only' not in sys.argv:
        ui.tap(*point)
        expected(' ')
        microphone(False)
        record('Short space tap inserts exactly one space and does not record')

        edit('前文后文', 2)
        ui.shell('am broadcast -a ' + ui.testpkg + '.SET_EDITOR -p ' + ui.testpkg + ' --es clipboard clipboard-sentinel')
        time.sleep(.8)
        down()
        microphone(True)
        ui.picture('space-hold-light-recording.png')
        inject()
        up()
        value = wait_transcript('前文', '后文')
        assert value.startswith('前文嗯') and not value.startswith('前文 ')
        assert not value.endswith(' 后文')
        clip = ui.shell('am broadcast -a ' + ui.testpkg + '.SET_EDITOR -p ' + ui.testpkg + ' --ez readClipboard true')
        assert 'clipboard-sentinel' in clip
        microphone(False)
        ui.picture('space-hold-direct-insert.png')
        record('Hold space records; release transcribes raw speech directly at the original cursor, preserves surrounding text and clipboard, and opens no preview')

        edit('取消保留', 4)
        time.sleep(.5)
        down()
        microphone(True)
        inject(1)
        ui.shell('input touchscreen motionevent MOVE ' + str(point[0]) + ' ' + str(point[1] - 320))
        microphone(False)
        up()
        time.sleep(1)
        expected('取消保留')
        record('Sliding out cancels recording and releasing adds no space or transcript')

        edit('位置变化', 4)
        time.sleep(.5)
        down()
        microphone(True)
        edit(start=1)
        microphone(False)
        up()
        expected('位置变化')
        record('Moving the cursor during recording cancels and preserves editor text')

        edit('等待取消', 4)
        time.sleep(.5)
        network_delay('1000 1000')
        try:
            down()
            microphone(True)
            inject(2)
            up()
            edit(start=0)
            microphone(False)
            time.sleep(3)
            expected('等待取消')
        finally:
            network_delay('none')
        record('Moving the cursor after release during a delayed cloud request cancels the result without insertion')

        edit('短录音', 3)
        time.sleep(.5)
        ui.shell('input touchscreen swipe ' + str(point[0]) + ' ' + str(point[1]) + ' ' + str(point[0]) + ' ' + str(point[1]) + ' 350')
        time.sleep(1)
        microphone(False)
        expected('短录音')
        record('Releasing immediately after the long-press threshold produces no late recording or accidental space')

        edit('password', 8, input_type=129)
        down()
        time.sleep(.8)
        up()
        microphone(False)
        expected('password')
        record('Password editors do not start dictation or receive unintended text')

    edit('', 0, input_type=1)
    ui.shell('run-as ' + ui.pkg + ' sh -c "echo dark-t9 > files/space-hold-command"')
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        if 'dark-t9' in ui.shell('run-as ' + ui.pkg + ' cat files/space-hold-command-done'):
            break
        time.sleep(.3)
    else:
        raise AssertionError('T9 layout change did not finish')
    time.sleep(3)
    point = (675, 2176)
    edit('九宫格', 3)
    time.sleep(.8)
    down()
    microphone(True)
    ui.picture('space-hold-dark-t9-recording.png')
    inject()
    up()
    wait_transcript('九宫格')
    microphone(False)
    record('Dark T9 space key also supports recording and direct transcript insertion')

    ui.shell('run-as ' + ui.pkg + ' sh -c "echo inline-on > files/space-hold-command"')
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        if 'inline-on' in ui.shell('run-as ' + ui.pkg + ' cat files/space-hold-command-done'):
            break
        time.sleep(.3)
    else:
        raise AssertionError('Inline preview setting did not finish')
    edit('', 0)
    time.sleep(.8)
    ui.type_keys(ui.t9_nihao)
    time.sleep(1)
    composing = current()
    assert composing
    down()
    microphone(True)
    inject()
    up()
    wait_transcript(composing)
    microphone(False)
    record('Dictation preserves the displayed unconfirmed composing text before appending the transcript')

    edit('前文替换后文', 2, 4)
    time.sleep(.5)
    down()
    microphone(True)
    inject()
    up()
    selected_value = wait_transcript('前文', '后文')
    assert '替换' not in selected_value
    microphone(False)
    record('Dictation replaces only the original selection and preserves its surrounding text')

    edit('', 0)
    ui.type_keys(ui.t9_nihao)
    ui.tap(*point)
    expected('你好')
    microphone(False)
    record('A short T9 space tap still confirms the composed Chinese candidate')

    edit('离开保留', 4)
    time.sleep(.8)
    down()
    microphone(True)
    ui.shell('input keyevent HOME')
    pressed = False
    microphone(False)
    record('Leaving the editor while holding cancels capture and releases the microphone')
    succeeded = True
    save(True)
finally:
    if pressed:
        ui.shell('input touchscreen motionevent UP ' + str(point[0]) + ' ' + str(point[1]))
    save(succeeded)
    ui.shell('run-as ' + ui.pkg + ' sh -c "echo ' + ('PASS' if succeeded else 'FAIL') + ' > files/space-hold-done"')
    ui.d.close()
