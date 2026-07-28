from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "server-bootstrap/v440-src"

futures = "".join((SRC / f"futures.{n:02d}").read_text() for n in range(5))
(SRC / "futures.py").write_text(futures)

apply_code = "".join((SRC / f"apply.{n:02d}").read_text() for n in range(4))
exec(compile(apply_code, str(SRC / "apply.generated.py"), "exec"))
