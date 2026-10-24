#!/usr/bin/env python3
"""Compiles the plugin against the SDK and runs the manifest and asset tests. No device needed."""
import os
import sys
from pathlib import Path
import subprocess
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from android_sdk import android_platform

root = Path(__file__).resolve().parents[1]
java_home = os.environ.get('JAVA_HOME')
def tool(name):
    return str(Path(java_home) / 'bin' / name) if java_home else name
# The SDK's overlay classes use Android views. The tests never draw one,
# so the platform's stub classes are enough to compile and load them.
sdk_root = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'android-sdk'))))
try:
    platform = str(android_platform(sdk_root))
except ValueError as error:
    raise SystemExit(str(error)) from error
sources = [*sorted((root / 'sdk/src').rglob('*.java')), *sorted((root / 'src').rglob('*.java')), *sorted((root / 'tests').rglob('*.java'))]
with tempfile.TemporaryDirectory(prefix='kiosk-plugin-test-') as directory:
    subprocess.run([tool('javac'), '--release', '8', '-cp', platform, '-d', directory, *map(str, sources)], check=True)

subprocess.run([sys.executable, str(root / 'tools/test_android_sdk.py')], check=True)

subprocess.run([sys.executable, str(root / 'tools/test_plugin_manifest.py')], check=True)

subprocess.run([sys.executable, str(root / "tools/test_plugin_assets.py")], check=True)



# Kiosk Satellite refuses the whole manifest over one bad action ID, so check them before a release does.
import json
import re
manifest = json.loads((root / 'kiosk-satellite-plugin.json').read_text(encoding='utf-8'))
for kind in ('commands', 'triggers', 'buttons'):
    ids = [item['id'] for item in manifest.get(kind, [])]
    bad = [i for i in ids if not re.fullmatch(r'[a-z][a-zA-Z0-9]{0,63}', i)]
    if bad or len(ids) != len(set(ids)):
        raise SystemExit(f'Invalid or duplicate {kind} IDs: {bad or ids}. Use camelCase such as showWindow.')
