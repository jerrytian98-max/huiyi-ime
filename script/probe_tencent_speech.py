"""Authorized synthetic-audio probe. Reads encrypted local defaults, never prints credentials."""
import argparse
import base64
import hashlib
import hmac
import json
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

parser = argparse.ArgumentParser()
parser.add_argument('--model', default='16k_zh')
parser.add_argument('--languages', nargs='+', choices=['zh', 'en'], default=['zh', 'en'])
parser.add_argument('--output-name', default='tencent-speech-host-result.json')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
values = dict(line.split('=', 1) for line in (root / 'local-tencent-asr-defaults.properties').read_text().splitlines() if '=' in line)
sealed = base64.b64decode(values['ciphertext'])
credentials = json.loads(AESGCM(base64.b64decode(values['wrap_key'])).decrypt(sealed[:12], sealed[12:], b'rime-ai:tencent-asr:v1'))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


opener = urllib.request.build_opener(NoRedirect())
rows = []
for language in args.languages:
    audio = (root / 'app/src/androidTest/assets' / ('speech-' + language + '.wav')).read_bytes()
    engine = '16k_en' if args.model == '16k_zh' and language == 'en' else args.model
    parameters = {
        'convert_num_mode': '1', 'engine_type': engine, 'filter_dirty': '0',
        'filter_modal': '0', 'filter_punc': '0', 'first_channel_only': '1',
        'secretid': credentials['secretId'], 'speaker_diarization': '0',
        'timestamp': str(int(time.time())), 'voice_format': 'wav', 'word_info': '0',
    }
    query = urllib.parse.urlencode(sorted(parameters.items()))
    target = 'asr.cloud.tencent.com/asr/flash/v1/' + credentials['appId'] + '?' + query
    signature = base64.b64encode(hmac.new(credentials['secretKey'].encode(), ('POST' + target).encode(), hashlib.sha1).digest()).decode()
    request = urllib.request.Request('https://' + target, audio, {'Authorization': signature, 'Content-Type': 'application/octet-stream'}, method='POST')
    started = time.monotonic()
    try:
        with opener.open(request, timeout=60) as response:
            status = response.status
            result = json.load(response)
    except urllib.error.HTTPError as error:
        status = error.code
        try:
            result = json.loads(error.read())
        except ValueError:
            result = {'code': status, 'message': 'Non-JSON HTTP response'}
    except Exception as error:
        rows.append({'language': language, 'exception_type': type(error).__name__})
        break
    text = '\n'.join(item.get('text', '') for item in result.get('flash_result', []))
    message = str(result.get('message', ''))
    for secret in credentials.values():
        if secret:
            message = message.replace(secret, '[redacted]')
    row = {'language': language, 'model': engine, 'http_status': status, 'code': result.get('code'), 'message': message,
           'request_id': result.get('request_id'), 'elapsed_ms': int((time.monotonic() - started) * 1000),
           'transcript': text, 'audio_bytes': len(audio)}
    rows.append(row)
    if result.get('code') != 0:
        break
output = {'provider': 'Tencent Cloud Flash ASR', 'model': args.model, 'source': 'Synthetic Chinese/English WAV fixtures, no physical microphone', 'checks': rows}
directory = root / 'build/test-evidence'
directory.mkdir(parents=True, exist_ok=True)
(directory / Path(args.output_name).name).write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding='utf-8')
for row in rows:
    print(json.dumps(row, ensure_ascii=True), flush=True)
