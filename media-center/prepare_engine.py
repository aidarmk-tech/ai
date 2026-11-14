"""Stage pinned upstream Android executables as installed native libraries (no runtime downloads)."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import urllib.request

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument('--emulator', action='store_true')
args = parser.parse_args()
lock = json.loads((root / 'media-center/engine.lock.json').read_text())
abis = ['x86_64'] if args.emulator else ['arm64-v8a', 'armeabi-v7a']
for abi in abis:
    artifact = lock['artifacts'][abi]
    destination = root / 'lampa-player-native/app/src/main/jniLibs' / abi / 'libtorrentengine.so'
    destination.parent.mkdir(parents=True, exist_ok=True)
    url = f"https://github.com/YouROK/TorrServer/releases/download/{lock['version']}/{artifact['asset']}"
    with urllib.request.urlopen(url, timeout=120) as response:
        data = response.read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != artifact['sha256']:
        raise RuntimeError(f'Upstream checksum mismatch for {abi}')
    expected_machine = {'arm64-v8a': 183, 'armeabi-v7a': 40, 'x86_64': 62}[abi]
    if data[:4] != b'\x7fELF' or struct.unpack_from('<H', data, 18)[0] != expected_machine:
        raise RuntimeError(f'Wrong ELF architecture for {abi}')
    destination.write_bytes(data)
    destination.chmod(0o755)
    print(f'{abi}: verified {len(data)} bytes, SHA-256 {digest}')
