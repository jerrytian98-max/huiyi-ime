"""Developer helper: connects directly to a local emulator, no adb server required.
Install dependency: python -m pip install adb-shell
Examples: python script/device.py shell "getprop sys.boot_completed"
          python script/device.py install app.apk
"""
import argparse
from adb_shell.adb_device import AdbDeviceTcp

parser = argparse.ArgumentParser()
parser.add_argument('--port', type=int, default=5557)
parser.add_argument('--timeout', type=int, default=60)
parser.add_argument('action', choices=['shell', 'install', 'push', 'pull', 'screenshot'])
parser.add_argument('args', nargs='*')
args = parser.parse_args()
d = AdbDeviceTcp('127.0.0.1', args.port, default_transport_timeout_s=60)
d.connect(auth_timeout_s=5)
if args.action == 'shell':
    for chunk in d.streaming_shell(' '.join(args.args), transport_timeout_s=args.timeout, read_timeout_s=args.timeout):
        print(chunk, end='', flush=True)
elif args.action == 'install':
    d.push(args.args[0], '/data/local/tmp/rime-ai-install.apk')
    print(d.shell('pm install -r -t /data/local/tmp/rime-ai-install.apk', transport_timeout_s=90, read_timeout_s=90, timeout_s=90))
elif args.action == 'push':
    d.push(*args.args)
elif args.action == 'pull':
    d.pull(*args.args)
else:
    d.shell('screencap -p /data/local/tmp/rime-ai-screen.png')
    d.pull('/data/local/tmp/rime-ai-screen.png', args.args[0])
d.close()
