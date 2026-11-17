"""Pixel 6 emulator: synthetic PCM -> Android microphone -> real ASR -> AI -> IME insert.

Requires the --audio test instrumentation and live_ai_relay.py --audio.
Emulator must use -grpc 8554 -grpc-use-token. Never enables the host microphone.
pip install adb-shell grpcio-tools
"""
import json
import os
from pathlib import Path
import re
import sys
import time
import wave
import xml.etree.ElementTree as ET

from adb_shell.adb_device import AdbDeviceTcp
import grpc
import grpc_tools
from grpc_tools import protoc

root = Path(__file__).resolve().parents[1]
evidence = root / 'build/test-evidence'
evidence.mkdir(parents=True, exist_ok=True)
bindings = root / 'build/emulator-grpc'
bindings.mkdir(parents=True, exist_ok=True)
proto = root.parent / '.tools/sdk/emulator/lib'
assert protoc.main(['grpc_tools.protoc', '-I'+str(proto), '-I'+str(Path(grpc_tools.__file__).parent/'_proto'), '--python_out='+str(bindings), '--grpc_python_out='+str(bindings), str(proto/'emulator_controller.proto')]) == 0
sys.path.insert(0, str(bindings))
import emulator_controller_pb2 as pb
import emulator_controller_pb2_grpc as rpc

settings = None
for discovery in (Path(os.environ['TEMP'])/'avd/running').glob('pid_*.ini'):
    values = dict(line.split('=', 1) for line in discovery.read_text().splitlines() if '=' in line)
    if values.get('grpc.port') == '8554':
        settings = values
assert settings and settings.get('grpc.token'), 'Authenticated emulator gRPC endpoint not found'
metadata = [('authorization', 'Bearer '+settings['grpc.token'])]
channel = grpc.insecure_channel('127.0.0.1:8554')
stub = rpc.EmulatorControllerStub(channel)
try:
    stub.setMicrophoneState(pb.MicrophoneState(realAudioEnabled=False), metadata=metadata, timeout=5)
except grpc.RpcError as error:
    raise SystemExit('Emulator microphone gRPC status: '+error.code().name) from None

d = AdbDeviceTcp('127.0.0.1', 5557, default_transport_timeout_s=20)
d.connect(auth_timeout_s=5)


def shell(command):
    return d.shell(command, transport_timeout_s=20, read_timeout_s=20, timeout_s=25)


def nodes():
    output = shell('uiautomator dump /data/local/tmp/rime-ai-audio.xml')
    if 'dumped to:' not in output:
        return []
    d.pull('/data/local/tmp/rime-ai-audio.xml', str(evidence/'audio-window.xml'))
    return list(ET.parse(evidence/'audio-window.xml').iter('node'))


def find(text=None, cls=None, timeout=30):
    deadline = time.monotonic()+timeout
    while time.monotonic() < deadline:
        current = nodes()
        if text == '插入整理稿' and any(n.get('text') == '语音转写未完成' for n in current):
            raise AssertionError('Voice failed: '+ascii([n.get('text') for n in current if n.get('text')]))
        for n in current:
            if (text is None or n.get('text') == text) and (cls is None or n.get('class') == cls):
                return n
        time.sleep(.3)
    raise AssertionError('Missing UI element: '+ascii(text or cls))


def tap_node(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    shell(f'input tap {(x1+x2)//2} {(y1+y2)//2}')


def tap(text):
    tap_node(find(text))


def screenshot(name):
    shell('screencap -p /data/local/tmp/rime-ai-audio.png')
    d.pull('/data/local/tmp/rime-ai-audio.png', str(evidence/name))


for _ in range(150):
    if 'READY' in shell('run-as com.rimeai.keyboard.debug cat files/audio-ui-ready'):
        break
    time.sleep(.5)
else:
    raise AssertionError('Audio test instrumentation not ready')
print('READY audio instrumentation', flush=True)

shell('settings put secure show_ime_with_hard_keyboard 1')
ime = 'com.rimeai.keyboard.debug/com.osfans.trime.ime.core.TrimeInputMethodService'
if shell('settings get secure default_input_method').strip() != ime:
    shell('ime enable '+ime)
    shell('ime set '+ime)
shell('pm grant com.rimeai.keyboard.debug android.permission.RECORD_AUDIO')
tap_node(find(cls='android.widget.EditText'))
time.sleep(5)
for _ in range(20):
    state = shell('dumpsys input_method')
    if 'mInputShown=true' in state:
        break
    time.sleep(.5)
shell('input tap 210 1585')  # IME toolbar is outside the accessibility root on this fixture.
find('正在录音 · 云端转写')
print('READY microphone recording', flush=True)
with wave.open(str(root/'app/src/androidTest/assets/speech-zh.wav'), 'rb') as audio:
    assert (audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) == (1, 2, 16000)
    mono = b'\x00'*6400 + audio.readframes(audio.getnframes()) + b'\x00'*12800
# Match the emulator's native 48 kHz stereo input; the guest AudioRecord resamples to 16 kHz.
pcm = b''.join(mono[index:index+2]*6 for index in range(0, len(mono), 2))


def packets():
    fmt = pb.AudioFormat(samplingRate=48000, channels=pb.AudioFormat.Stereo, format=pb.AudioFormat.AUD_FMT_S16, mode=pb.AudioFormat.MODE_REAL_TIME)
    started = time.monotonic()
    for offset in range(0, len(pcm), 3840):
        delay = started + offset/192000 - time.monotonic()
        if delay > 0:
            time.sleep(delay)
        yield pb.AudioPacket(format=fmt, timestamp=int(time.time()*1_000_000), audio=pcm[offset:offset+3840])


try:
    stub.injectAudio(packets(), metadata=metadata, timeout=45)
except grpc.RpcError as error:
    raise SystemExit('PCM injection status: '+error.code().name) from None
print('Injected synthetic PCM in real time', flush=True)
time.sleep(.6)
tap('完成')
screenshot('cloud-voice-after-recording.png')
find('插入整理稿', timeout=90)
preview = '\n'.join(n.get('text', '') for n in nodes())
assert '提纲' in preview and '预算' in preview, ascii(preview)
screenshot('cloud-voice-preview.png')
tap('插入整理稿')
inserted = find(cls='android.widget.EditText').get('text')
assert '提纲' in inserted and '预算' in inserted, ascii(inserted)
print('PASS injected PCM -> AudioRecord -> real ASR -> AI preview -> inserted text', flush=True)

shell('input tap 210 1585')
find('正在录音 · 云端转写')
tap('取消')
for _ in range(20):
    appops = shell('cmd appops get com.rimeai.keyboard.debug RECORD_AUDIO')
    if 'running' not in appops.lower():
        break
    time.sleep(.25)
else:
    raise AssertionError('Microphone still active after cancellation')
assert find(cls='android.widget.EditText').get('text') == inserted
print('PASS cancel stops microphone without changing text', flush=True)

# Starting again establishes that the cancelled capture released the recorder.
shell('input tap 210 1585')
find('正在录音 · 云端转写')
shell('input keyevent 3')  # Leaving the editor must cancel recording.
time.sleep(1)
appops = shell('cmd appops get com.rimeai.keyboard.debug RECORD_AUDIO')
assert 'running' not in appops.lower(), 'Microphone active after editor hidden'
print('PASS hiding editor releases microphone', flush=True)

(evidence/'cloud-voice-ui-result.json').write_text(json.dumps({
    'audio_source': 'Synthetic Windows TTS injected into emulator microphone; host microphone disabled',
    'passed': ['PCM -> Android AudioRecord -> real ASR -> AI preview -> insert', 'cancel releases microphone and preserves text', 'editor hidden releases microphone'],
    'inserted': inserted,
    'preview': preview,
    'final_microphone_appops': appops,
}, ensure_ascii=False, indent=2), encoding='utf-8')
shell('run-as com.rimeai.keyboard.debug sh -c "echo PASS > files/finish-audio-checks"')
d.close()
channel.close()
