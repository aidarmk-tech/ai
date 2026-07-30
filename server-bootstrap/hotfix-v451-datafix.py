#!/usr/bin/env python3
from __future__ import annotations
import argparse, gzip, hashlib, io, os, tarfile, tempfile
from pathlib import Path
EXPECTED_BASE_SHA256={'pumpradar_server/__init__.py': 'a8046de2d579ffa16b047609c03b07ea147925c5dcbf0f2176afa54a19c84624', 'pumpradar_server/config.py': 'e9884477c0a1eecca4726fcfa0c7f75a7ea38bf4bf51b9f1403de31ddc94bcd6', 'pumpradar_server/main.py': '63e939fcaccd350d90927015f97f37de2fb2dd225bbe8513716e5334ce9d3767', 'pumpradar_server/regime.py': '98a22efc9cdc86ade35d084e7c4c30daab642541a6233e6bd2ec901f0cb4a5b2', 'pumpradar_server/storage.py': '38f2933524d8c903064e1163bd1ab8507b58f629bed08b8345c56bcccbe16af2'}
EXPECTED_FINAL_SHA256={'pumpradar_server/__init__.py': '45befe9cdd348ceeed575cb43fc809eee994df185487c8dd163d3176fb25e9e6', 'pumpradar_server/config.py': 'e815a4e2cbee70db4d1c8483782b5dbc1baeef5c605f003ce0e8810e0c34145a', 'pumpradar_server/main.py': '2fca8df06421a85ccef458fa1d545e50d6196e24ee0fa79327d83bf706d5a6ee', 'pumpradar_server/regime.py': '37439f44b8fa3c502d7fd62916f76718eea97a4ef5d7ff648f4f7cd69e18ec52', 'pumpradar_server/storage.py': '070f14bb91fbe9486bbb4af9b90c4f0ba6b30c77623a4aad15a9316d29328bef', 'tests/test_v451_regime.py': '9de164a8a96a88d0901163b7a9368db7fc8d2f2d40ab07c184ceaf104f958fc0'}
EXPECTED_DEPLOY_SHA256={'pumpradar-watchdog': '6a6fbe870a79238eb407fecf23ef7fc81cb0371b527a71d307df80c4a7173eb4', 'pumpradar-watchdog.service': '7e1ecb8ed8a2840aa66b94309998c2ddfd024d5783d0acef3180539add6d4c86', 'pumpradar-watchdog.timer': 'd7c9aea167b01d551031ef347d934de48553d2bba02a2dbaf06aa46ebdf08486', '20-watchdog-stop-timeout.conf': '9eabf574a62c1cf406cda76814bbe4eb14d68f8d96820c9e6c0fd0e869b171b6'}
PAYLOAD_SHA256='4133274f70a501fe88f657add95d561cf50ea57f5d5a0c5033e8da89c1f0316c'

def sha256(path: Path) -> str:
 h=hashlib.sha256()
 with path.open('rb') as f:
  for chunk in iter(lambda:f.read(1024*1024),b''): h.update(chunk)
 return h.hexdigest()

def atomic_write(path: Path,data: bytes,mode: int)->None:
 path.parent.mkdir(parents=True,exist_ok=True)
 fd,tmp_name=tempfile.mkstemp(prefix=path.name+'.',dir=path.parent); tmp=Path(tmp_name)
 try:
  with os.fdopen(fd,'wb') as f:
   f.write(data); f.flush(); os.fsync(f.fileno())
  os.chmod(tmp,mode); os.replace(tmp,path)
 finally:
  try: tmp.unlink()
  except FileNotFoundError: pass

def main()->int:
 ap=argparse.ArgumentParser(description='Apply PumpRadar v4.5.1 data-integrity hotfix')
 ap.add_argument('candidate_root',type=Path); ap.add_argument('deploy_dir',type=Path); ap.add_argument('payload_gz',type=Path)
 a=ap.parse_args(); root=a.candidate_root.resolve(); deploy=a.deploy_dir.resolve(); payload=a.payload_gz.resolve()
 if not (root/'pumpradar_server').is_dir(): raise SystemExit(f'Invalid candidate root: {root}')
 for rel,base in EXPECTED_BASE_SHA256.items():
  path=root/rel
  if not path.is_file(): raise SystemExit(f'Missing live source file: {path}')
  actual=sha256(path); final=EXPECTED_FINAL_SHA256[rel]
  if actual not in {base,final}: raise SystemExit(f'Unexpected source SHA-256 for {rel}: {actual}; expected {base} or {final}')
 if sha256(payload)!=PAYLOAD_SHA256: raise SystemExit(f'Payload SHA-256 mismatch: {sha256(payload)}')
 raw=gzip.decompress(payload.read_bytes()); allowed={'app/'+x for x in EXPECTED_FINAL_SHA256}|{'deploy/'+x for x in EXPECTED_DEPLOY_SHA256}; seen=set()
 with tarfile.open(fileobj=io.BytesIO(raw),mode='r:') as tf:
  for m in tf.getmembers():
   if m.name not in allowed or not m.isfile(): raise SystemExit(f'Unexpected payload member: {m.name}')
   src=tf.extractfile(m)
   if src is None: raise SystemExit(f'Cannot read payload member: {m.name}')
   data=src.read()
   if m.name.startswith('app/'):
    rel=m.name[4:]; dest=root/rel; expected=EXPECTED_FINAL_SHA256[rel]; mode=0o644
   else:
    rel=m.name[7:]; dest=deploy/rel; expected=EXPECTED_DEPLOY_SHA256[rel]; mode=0o755 if rel=='pumpradar-watchdog' else 0o644
   actual=hashlib.sha256(data).hexdigest()
   if actual!=expected: raise SystemExit(f'Payload member SHA-256 mismatch for {m.name}: {actual}')
   atomic_write(dest,data,mode); seen.add(m.name)
 if seen!=allowed: raise SystemExit(f'Incomplete payload; missing: {sorted(allowed-seen)}')
 for rel,expected in EXPECTED_FINAL_SHA256.items():
  actual=sha256(root/rel)
  if actual!=expected: raise SystemExit(f'Post-write mismatch for {rel}: {actual}')
 for rel,expected in EXPECTED_DEPLOY_SHA256.items():
  actual=sha256(deploy/rel)
  if actual!=expected: raise SystemExit(f'Post-write deploy mismatch for {rel}: {actual}')
 print('PumpRadar v4.5.1 patch applied and verified')
 return 0
if __name__=='__main__': raise SystemExit(main())
