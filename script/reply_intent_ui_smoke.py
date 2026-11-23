"""Drive the intent-reply UI instrumentation with synthetic emulator microphone audio only."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import wave

import grpc
import grpc_tools
from grpc_tools import protoc

root = Path(__file__).resolve().parents[1]
adb = root.parent / '.tools/sdk/platform-tools/adb.exe'
serial = os.environ.get('HUIYI_EMULATOR_SERIAL', 'emulator-5556')
pkg = 'com.rimeai.keyboard.debug'
flags = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
evidence = root / 'build/test-evidence'
evidence.mkdir(parents=True, exist_ok=True)
bindings = root / 'build/emulator-grpc'
bindings.mkdir(parents=True, exist_ok=True)
proto = root.parent / '.tools/sdk/emulator/lib'
assert protoc.main(['grpc_tools.protoc', '-I' + str(proto), '-I' + str(Path(grpc_tools.__file__).parent / '_proto'), '--python_out=' + str(bindings), '--grpc_python_out=' + str(bindings), str(proto / 'emulator_controller.proto')]) == 0
sys.path.insert(0, str(bindings))
import emulator_controller_pb2 as pb
import emulator_controller_pb2_grpc as rpc

discovery = next((dict(line.split('=', 1) for line in file.read_text().splitlines() if '=' in line)
                  for file in (Path(os.environ['TEMP']) / 'avd/running').glob('pid_*.ini')
                  if 'grpc.port=8554' in file.read_text()), None)
assert discovery, 'Local emulator audio endpoint missing'
metadata = [('authorization', 'Bearer ' + discovery['grpc.token'])] if discovery.get('grpc.token') else []
stub = rpc.EmulatorControllerStub(grpc.insecure_channel('127.0.0.1:8554'))


def shell(*args):
    return subprocess.run([str(adb), '-s', serial, 'shell', *args], capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=20, creationflags=flags)


for permission in ('android.permission.RECORD_AUDIO', 'android.permission.POST_NOTIFICATIONS'):
    shell('pm', 'grant', pkg, permission)
shell('ime', 'enable', pkg + '/com.osfans.trime.ime.core.TrimeInputMethodService')
shell('ime', 'set', pkg + '/com.osfans.trime.ime.core.TrimeInputMethodService')
shell('settings', 'put', 'secure', 'accessibility_enabled', '0')
shell('settings', 'delete', 'secure', 'enabled_accessibility_services')
stub.setMicrophoneState(pb.MicrophoneState(realAudioEnabled=False), metadata=metadata, timeout=5)
log_path = evidence / 'reply-intent-ui-instrumentation.log'
with log_path.open('wb') as log:
    process = subprocess.Popen([str(adb), '-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'replyIntentUi', 'true', pkg + '.test/com.osfans.trime.ai.AiIntegrationInstrumentation'], stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
    try:
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(log_path.read_text(errors='replace'))
            if shell('run-as', pkg, 'cat', 'files/reply-intent-audio-ready').stdout.strip() == 'recording':
                break
            time.sleep(.4)
        else:
            raise RuntimeError('Instrumentation did not reach the recording step')
        deadline = time.monotonic() + 10
        while 'running' not in shell('cmd', 'appops', 'get', pkg, 'RECORD_AUDIO').stdout.lower():
            assert time.monotonic() < deadline, 'AudioRecord did not become active'
            time.sleep(.2)
        with wave.open(str(root / 'app/src/androidTest/assets/speech-zh.wav'), 'rb') as source:
            mono = b'\x00' * 6400 + source.readframes(source.getnframes()) + b'\x00' * 12800
        stereo = b''.join(mono[index:index + 2] * 6 for index in range(0, len(mono), 2))

        def packets():
            audio_format = pb.AudioFormat(samplingRate=48000, channels=pb.AudioFormat.Stereo, format=pb.AudioFormat.AUD_FMT_S16, mode=pb.AudioFormat.MODE_REAL_TIME)
            started = time.monotonic()
            for offset in range(0, len(stereo), 3840):
                remaining = started + offset / 192000 - time.monotonic()
                if remaining > 0:
                    time.sleep(remaining)
                yield pb.AudioPacket(format=audio_format, timestamp=int(time.time() * 1_000_000), audio=stereo[offset:offset + 3840])
        stub.injectAudio(packets(), metadata=metadata, timeout=30)
        shell('run-as', pkg, 'touch', 'files/reply-intent-audio-done')
        process.wait(timeout=110)
        response = shell('run-as', pkg, 'cat', 'files/reply-intent-ui-result.json')
        result = json.loads(response.stdout)
        (evidence / 'reply-intent-ui-result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        assert result.get('passed'), log_path.read_text(errors='replace')
        print(json.dumps(result, ensure_ascii=False, indent=2))
    finally:
        if process.poll() is None:
            shell('am', 'force-stop', pkg)
            process.terminate()
