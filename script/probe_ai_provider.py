"""Explicit live-provider smoke check using synthetic keyboard inputs only.

The API key is read without echo. By default it stays in memory; the explicit
internal-default option writes an encrypted, ignored local build input only.
Endpoint redirects are rejected, matching the Android client's policy.
"""
import argparse
import base64
import getpass
import json
from pathlib import Path
import re
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--endpoint', required=True)
parser.add_argument('--model', required=True)
parser.add_argument('--disable-thinking', action='store_true')
parser.add_argument('--no-think', action='store_true', help='Probe the native Hy4 no_think parameter')
parser.add_argument('--output-name', default='tokenlink-hy4-host-result.json')
parser.add_argument('--prepare-bundled-default', action='store_true', help='Encrypt an authorized internal-test default credential into an ignored local build input')
parser.add_argument('--cases', nargs='+', choices=['greeting', 'zh', 'en', 'vision'], default=['greeting', 'zh', 'en', 'vision'])
parser.add_argument('--prepare-only', action='store_true', help='Prepare the encrypted default without making API requests')
args = parser.parse_args()
if args.prepare_only and not args.prepare_bundled_default:
    parser.error('--prepare-only requires --prepare-bundled-default')
if not args.endpoint.startswith('https://'):
    parser.error('HTTPS endpoint required')
root = Path(__file__).resolve().parents[1]
key = getpass.getpass('API Key (not saved): ')
if not key:
    raise SystemExit('No key supplied')
if args.prepare_bundled_default:
    if args.endpoint != 'https://api.deepseek.com/chat/completions' or args.model != 'deepseek-flash':
        raise SystemExit('Bundled default is bound to the verified DeepSeek profile')
    import os
    import hashlib
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    wrapping_key = AESGCM.generate_key(bit_length=256)
    nonce = os.urandom(12)
    sealed = nonce + AESGCM(wrapping_key).encrypt(nonce, key.encode(), b'rime-ai:deepseek-flash:v1')
    local = root/'local-ai-defaults.properties'
    local.write_text('ciphertext='+base64.b64encode(sealed).decode()+'\nwrap_key='+base64.b64encode(wrapping_key).decode()+'\nid='+hashlib.sha256(sealed).hexdigest()[:16]+'\n', 'utf-8')
    print('Encrypted internal-test default prepared; no plaintext credential written', flush=True)
    if args.prepare_only:
        raise SystemExit(0)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


opener = urllib.request.build_opener(NoRedirect())
source = (root/'app/src/main/java/com/osfans/trime/ai/AiClient.kt').read_text('utf-8')
polish_system = re.search(r'message\("system", "(You edit dictated.*?)"\)', source).group(1)
vision_system = re.search(r'message\("system", "(你是谨慎的聊天回复助手.*?)"\)', source).group(1)
vision_prompt = re.search(r'put\("text", "(这是一张用户主动截取.*?)"\)', source).group(1).replace('\\"', '"')
inputs = {
    'zh': '嗯那个明天下午三点开会然后请Alex提前发一下提纲预算是1500元还没确定',
    'en': 'um so tomorrow at 3 pm we meet and Alex should send the outline first the budget is 1500 dollars but it is not confirmed yet',
}
result = {'endpoint': args.endpoint, 'model': args.model, 'disable_thinking': args.disable_thinking, 'no_think': args.no_think, 'credential_saved': args.prepare_bundled_default, 'plaintext_credential_saved': False, 'encrypted_build_default_prepared': args.prepare_bundled_default, 'transport': 'host HTTPS; Android client not exercised by this probe', 'checks': []}
evidence = root/'build/test-evidence'/Path(args.output_name).name

for case in args.cases:
    stream = case in ('zh', 'en')
    if case == 'greeting':
        body = {'model': args.model, 'messages': [{'role': 'user', 'content': '你好，介绍一下你自己'}], 'stream': False}
    elif stream:
        body = {'model': args.model, 'messages': [{'role': 'system', 'content': polish_system}, {'role': 'user', 'content': inputs[case]}], 'max_tokens': 3000, 'temperature': 0.3, 'stream': True}
    else:
        image = base64.b64encode((root/'app/src/androidTest/assets/synthetic-chat.jpg').read_bytes()).decode()
        body = {'model': args.model, 'messages': [{'role': 'system', 'content': vision_system}, {'role': 'user', 'content': [{'type': 'text', 'text': vision_prompt}, {'type': 'image_url', 'image_url': {'url': 'data:image/jpeg;base64,'+image, 'detail': 'high'}}]}], 'max_tokens': 1000, 'temperature': 0.3}
    if args.disable_thinking:
        body['thinking'] = {'type': 'disabled'}
    if args.no_think:
        body['reasoning_effort'] = 'no_think'
        body['chat_template_kwargs'] = {'reasoning_effort': 'no_think'}
    row = {'case': case, 'requested_stream': stream}
    started = time.monotonic()
    try:
        req = urllib.request.Request(args.endpoint, data=json.dumps(body, ensure_ascii=False).encode(), headers={'Authorization': 'Bearer '+key, 'Content-Type': 'application/json; charset=utf-8', 'Accept': 'text/event-stream, application/json' if stream else 'application/json'})
        with opener.open(req, timeout=45) as response:
            row.update(http_status=response.status, content_type=response.headers.get('Content-Type'))
            row['headers_ms'] = round((time.monotonic()-started)*1000)
            if 'text/event-stream' in row['content_type'].lower():
                output = []
                finishes = []
                events = 0
                size = 0
                for raw in response:
                    size += len(raw)
                    if size > 2*1024*1024: raise ValueError('Response exceeds Android client limit')
                    line = raw.decode('utf-8').strip()
                    if not line.startswith('data:'): continue
                    data = line[5:].strip()
                    if data == '[DONE]': break
                    chunk = json.loads(data)
                    if chunk.get('error'): raise ValueError(json.dumps(chunk['error'], ensure_ascii=False))
                    for choice in chunk.get('choices', []):
                        delta = choice.get('delta', {})
                        text = delta.get('content') or ''
                        if not isinstance(text, str): raise ValueError('Non-string delta.content')
                        if text:
                            row.setdefault('first_content_ms', round((time.monotonic()-started)*1000))
                            output.append(text)
                            events += 1
                        if choice.get('finish_reason') is not None: finishes.append(choice['finish_reason'])
                row.update(output=''.join(output).strip(), finish_reasons=finishes, content_events=events)
                if not row['output'] or not finishes or any(x != 'stop' for x in finishes): raise ValueError('Incomplete or empty stream under Android parser rules')
            else:
                payload = json.loads(response.read(2*1024*1024+1))
                if payload.get('error'): raise ValueError(json.dumps(payload['error'], ensure_ascii=False))
                choice = payload['choices'][0]
                row.update(output=choice['message']['content'], finish_reasons=[choice.get('finish_reason')], returned_model=payload.get('model'), usage=payload.get('usage'))
                if not row['output'] or choice.get('finish_reason') not in (None, 'stop'): raise ValueError('Incomplete or empty completion under Android parser rules')
        if case in inputs: row['input'] = inputs[case]
        if case == 'vision':
            cleaned = row['output'].strip().removeprefix('```json').removeprefix('```').removesuffix('```').strip()
            parsed = json.loads(cleaned)
            suggestions = parsed.get('suggestions')
            if not isinstance(suggestions, list) or len(suggestions) != 3 or any(not isinstance(x, str) or not 0 < len(x) <= 600 for x in suggestions): raise ValueError('Expected three valid suggestions')
            row['parsed'] = parsed
        row['passed'] = True
    except urllib.error.HTTPError as error:
        row.update(passed=False, http_status=error.code, error=error.read(8192).decode('utf-8', errors='replace').replace(key, '[redacted]'))
    except Exception as error:
        row.update(passed=False, error_type=type(error).__name__, error=str(error).replace(key, '[redacted]'))
    row['total_ms'] = round((time.monotonic()-started)*1000)
    result['checks'].append(row)
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text(json.dumps(result, ensure_ascii=False, indent=2).replace(key, '[redacted]'), 'utf-8')
    print(json.dumps(row, ensure_ascii=False).replace(key, '[redacted]'), flush=True)
    # Avoid repeating requests when the network, authentication or route is unavailable.
    if not row['passed'] and ('http_status' not in row or row['http_status'] in (401, 403, 404)):
        break
key = ''
