"""Opt-in, three-request real-provider check for Android instrumentation.

Uses OPENAI_API_KEY in host memory only. Only synthetic fixtures may be sent.
Run mock_ai_server.py --prepare-only first to prepare the test-only TLS certificate.
The production APK never trusts this certificate or uses this relay.
"""
import argparse
import getpass
from email.parser import BytesParser
from email.policy import default
import json
import os
from pathlib import Path
import ssl
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--audio', action='store_true', help='Allow six synthetic audio/text requests for audio checks')
parser.add_argument('--requests', type=int, choices=range(1, 7), help='Bound the number of requests for a focused retest')
parser.add_argument('--capture-audio', action='store_true', help='Save synthetic uploaded WAV only, for emulator transport diagnostics')
parser.add_argument('--endpoint', default='https://api.openai.com/v1/chat/completions')
parser.add_argument('--model', default='gpt-4o-mini')
parser.add_argument('--key-prompt', action='store_true', help='Read the explicitly supplied key without echo or persistence')
args = parser.parse_args()
if not args.endpoint.startswith('https://'):
    parser.error('HTTPS endpoint required')
if args.audio and args.endpoint != 'https://api.openai.com/v1/chat/completions':
    parser.error('Audio relay requires the original OpenAI audio configuration')
key = getpass.getpass('API Key (not saved): ') if args.key_prompt else os.environ.get('OPENAI_API_KEY', '')
if not key:
    raise SystemExit('Provide a key with --key-prompt or OPENAI_API_KEY before this opt-in paid test.')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


opener = urllib.request.build_opener(NoRedirect())


class Handler(BaseHTTPRequestHandler):
    requests_left = args.requests or (6 if args.audio else 3)

    def log_message(self, *_):
        pass

    def do_POST(self):
        allowed = ('/v1/chat/completions', '/v1/audio/transcriptions') if args.audio else ('/v1/chat/completions',)
        if (self.path not in allowed
                or self.headers.get('Authorization') != 'Bearer synthetic-test-key'
                or Handler.requests_left <= 0):
            self.send_error(403)
            return
        size = int(self.headers.get('Content-Length', '0'))
        if not 0 < size < 2 * 1024 * 1024:
            self.send_error(413)
            return
        payload = self.rfile.read(size)
        content_type = self.headers.get('Content-Type', '')
        if self.path.endswith('/transcriptions'):
            form = BytesParser(policy=default).parsebytes(('Content-Type: '+content_type+'\r\nMIME-Version: 1.0\r\n\r\n').encode()+payload)
            fields = {part.get_param('name', header='content-disposition'): part.get_payload(decode=True) for part in form.iter_parts()}
            model = fields.get('model', b'').decode()
            valid = model == 'gpt-4o-mini-transcribe' and fields.get('file', b'').startswith(b'RIFF')
            if args.capture_audio and valid:
                evidence = root / 'build/test-evidence'
                evidence.mkdir(parents=True, exist_ok=True)
                (evidence / 'injected-capture.wav').write_bytes(fields['file'])
        else:
            valid = json.loads(payload).get('model') == args.model
        if not valid:
            self.send_error(400)
            return
        Handler.requests_left -= 1
        print(json.dumps({'path': self.path, 'remaining_requests': Handler.requests_left}), flush=True)
        request = urllib.request.Request(
            args.endpoint if self.path.endswith('/chat/completions') else 'https://api.openai.com'+self.path, data=payload,
            headers={'Content-Type': content_type, 'Authorization': 'Bearer ' + key},
        )
        try:
            with opener.open(request, timeout=35) as response:
                self.send_response(response.status)
                self.send_header('Content-Type', response.headers.get('Content-Type', 'application/json'))
                self.end_headers()
                # readline forwards each SSE event promptly for first-content timing.
                while chunk := response.readline(65536):
                    self.wfile.write(chunk)
                    self.wfile.flush()
        except urllib.error.HTTPError as error:
            self.send_error(error.code)
        except Exception as error:
            print(json.dumps({'relay_error_type': type(error).__name__}), flush=True)
            self.close_connection = True
        finally:
            if Handler.requests_left == 0:
                threading.Thread(target=self.server.shutdown, daemon=True).start()


server = ThreadingHTTPServer(('127.0.0.1', 9444), Handler)
tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
cert_dir = root.parent / '.tools' / 'mock-api'
tls.load_cert_chain(cert_dir / 'certificate.pem', cert_dir / 'private-key.pem')
server.socket = tls.wrap_socket(server.socket, server_side=True)
print(f'Real-provider relay ready; {Handler.requests_left} synthetic requests maximum on 127.0.0.1:9444', flush=True)
server.serve_forever()
server.server_close()
