#!/usr/bin/env python3
"""Slice giant XMLTV guides into tiny per-channel JSON files.

Reads sources.txt (one XMLTV .xml/.xml.gz URL per line) and ids.txt (wanted
tvg-ids), keeps programmes in a [-12h, +36h] window and writes
out/epg/<tvg-id>.json:  {"n": "Channel name", "p": [{"s": startSec,
"e": stopSec, "t": "Title", "d": "Description"}]}

Runs in CI (GitHub Actions cron); the player fetches single files by tvg-id —
a TV box never touches the 400 MB guide itself.
"""
import gzip
import io
import json
import os
import re
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

WINDOW_PAST = 12 * 3600
WINDOW_FUTURE = 36 * 3600
MAX_DESC = 240

here = os.path.dirname(os.path.abspath(__file__))
out_dir = os.path.join(here, "out", "epg")
os.makedirs(out_dir, exist_ok=True)

with open(os.path.join(here, "ids.txt"), encoding="utf-8") as f:
    wanted = {line.strip() for line in f if line.strip()}
with open(os.path.join(here, "sources.txt"), encoding="utf-8") as f:
    sources = [line.strip() for line in f if line.strip() and not line.startswith("#")]

now = time.time()
lo, hi = now - WINDOW_PAST, now + WINDOW_FUTURE

TIME_RE = re.compile(r"^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})\s*([+-]\d{4})?")


def parse_time(s):
    m = TIME_RE.match(s or "")
    if not m:
        return None
    y, mo, d, h, mi, se = (int(m.group(i)) for i in range(1, 7))
    import calendar
    t = calendar.timegm((y, mo, d, h, mi, se, 0, 0, 0))
    if m.group(7):
        off = int(m.group(7)[1:3]) * 3600 + int(m.group(7)[3:5]) * 60
        t -= off if m.group(7)[0] == "+" else -off
    return t


channels = {}   # id -> {"n": name, "p": [...]}
found_ids = set()

for url in sources:
    print(f"→ {url}", flush=True)
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "epg-slicer"})
        resp = urllib.request.urlopen(req, timeout=120)
        stream = resp
        if url.endswith(".gz"):
            stream = gzip.GzipFile(fileobj=io.BufferedReader(resp, 1 << 20))
        count = 0
        for event, el in ET.iterparse(stream, events=("end",)):
            tag = el.tag
            if tag == "channel":
                cid = el.get("id") or ""
                if cid in wanted and cid not in channels:
                    name = next((c.text for c in el if c.tag == "display-name" and c.text), "")
                    channels[cid] = {"n": name.strip(), "p": []}
                el.clear()
            elif tag == "programme":
                cid = el.get("channel") or ""
                if cid in wanted:
                    start = parse_time(el.get("start"))
                    stop = parse_time(el.get("stop"))
                    if start and stop and stop > lo and start < hi:
                        title = desc = ""
                        for c in el:
                            if c.tag == "title" and c.text and not title:
                                title = c.text.strip()
                            elif c.tag == "desc" and c.text and not desc:
                                desc = c.text.strip()[:MAX_DESC]
                        if title:
                            ch = channels.setdefault(cid, {"n": "", "p": []})
                            ch["p"].append({"s": int(start), "e": int(stop), "t": title, "d": desc})
                            found_ids.add(cid)
                count += 1
                if count % 200000 == 0:
                    print(f"  … {count} programmes scanned", flush=True)
                el.clear()
        print(f"  done: {count} programmes, channels matched so far: {len(found_ids)}", flush=True)
    except Exception as e:  # a dead source must not kill the whole build
        print(f"  SOURCE FAILED: {e}", file=sys.stderr, flush=True)

written = 0
for cid, ch in channels.items():
    if not ch["p"]:
        continue
    ch["p"].sort(key=lambda p: p["s"])
    safe = re.sub(r"[^A-Za-z0-9._-]", "_", cid)
    with open(os.path.join(out_dir, safe + ".json"), "w", encoding="utf-8") as f:
        json.dump(ch, f, ensure_ascii=False, separators=(",", ":"))
    written += 1

with open(os.path.join(out_dir, "_meta.json"), "w", encoding="utf-8") as f:
    json.dump({"ts": int(now), "channels": written, "wanted": len(wanted)}, f)

print(f"WROTE {written}/{len(wanted)} channel files")
if written == 0:
    sys.exit(1)
