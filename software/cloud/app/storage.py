import json
import re
import threading
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

from .config import settings

_meta_locks: dict[str, threading.Lock] = {}
_meta_locks_guard = threading.Lock()

FOLDER_TZ = ZoneInfo("Europe/Vilnius")


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def new_session_id(participant_code: str, started_at: datetime | None = None) -> str:
    """Readable session folder id: P001_20260713_0125."""
    participant = _sanitize_participant(participant_code)
    started = started_at or utc_now()
    local = started.astimezone(FOLDER_TZ)
    stamp = local.strftime("%Y%m%d_%H%M")
    base = f"{participant}_{stamp}"
    return ensure_unique_session_id(base)


def ensure_unique_session_id(base_name: str) -> str:
    existing: set[str] = set()
    for root in (settings.protocol_root, settings.raw_root):
        if not root.exists():
            continue
        for path in root.iterdir():
            if path.is_dir():
                existing.add(path.name)
    if base_name not in existing:
        return base_name
    suffix = 2
    while f"{base_name}_{suffix:02d}" in existing:
        suffix += 1
    return f"{base_name}_{suffix:02d}"


def _sanitize_participant(participant_code: str) -> str:
    cleaned = re.sub(r"[^\w-]", "", participant_code.strip().upper())
    return cleaned or "UNKNOWN"


def build_folder_name(participant_code: str, started_at: datetime) -> str:
    """Human-readable OneDrive folder: YYYY-MM-DD_HHMM_PARTICIPANT (Vilnius time)."""
    participant = _sanitize_participant(participant_code)
    local = started_at.astimezone(FOLDER_TZ)
    return f"{local.strftime('%Y-%m-%d_%H%M')}_{participant}"


def ensure_unique_folder_name(base_name: str) -> str:
    """Avoid collisions when same participant runs multiple sessions same minute."""
    existing = set()
    if settings.protocol_root.exists():
        for path in settings.protocol_root.iterdir():
            if not path.is_dir():
                continue
            session_file = path / "session.json"
            if session_file.exists():
                data = read_json(session_file)
                if data.get("folder_name"):
                    existing.add(data["folder_name"])
    if base_name not in existing:
        return base_name
    suffix = 2
    while f"{base_name}_{suffix:02d}" in existing:
        suffix += 1
    return f"{base_name}_{suffix:02d}"


def folder_name_for_session(session_id: str) -> str:
    """Folder label for OneDrive export (generated for legacy sessions too)."""
    session = read_json(protocol_session_path(session_id))
    if session.get("folder_name"):
        return session["folder_name"]
    started = datetime.fromisoformat(session["started_at"])
    base = build_folder_name(session["participant_code"], started)
    return ensure_unique_folder_name(base)


def session_raw_dir(session_id: str) -> Path:
    path = settings.raw_root / session_id
    path.mkdir(parents=True, exist_ok=True)
    return path


def session_protocol_dir(session_id: str) -> Path:
    path = settings.protocol_root / session_id
    path.mkdir(parents=True, exist_ok=True)
    return path


def raw_imu_path(session_id: str) -> Path:
    """Legacy alias for accelerometer stream."""
    return raw_stream_path(session_id, "accelerometer")


def raw_stream_path(session_id: str, stream: str) -> Path:
    normalized = stream.strip().lower()
    if normalized in {"imu", "accel"}:
        normalized = "accelerometer"
    return session_raw_dir(session_id) / f"{normalized}.jsonl"


def raw_capabilities_path(session_id: str) -> Path:
    return session_raw_dir(session_id) / "capabilities.json"


def list_raw_stream_files(session_id: str) -> list[Path]:
    root = session_raw_dir(session_id)
    if not root.exists():
        return []
    return sorted(path for path in root.glob("*.jsonl") if path.is_file())


def count_raw_samples(session_id: str) -> tuple[int, dict[str, int]]:
    counts: dict[str, int] = {}
    total = 0
    for path in list_raw_stream_files(session_id):
        stream = path.stem
        count = count_jsonl_lines(path)
        counts[stream] = count
        total += count
    legacy = session_raw_dir(session_id) / "imu.jsonl"
    if legacy.exists() and "accelerometer" not in counts:
        legacy_count = count_jsonl_lines(legacy)
        counts["accelerometer"] = legacy_count
        total += legacy_count
    return total, counts


def raw_meta_path(session_id: str) -> Path:
    return session_raw_dir(session_id) / "meta.json"


def protocol_session_path(session_id: str) -> Path:
    return session_protocol_dir(session_id) / "session.json"


def protocol_shots_path(session_id: str) -> Path:
    return session_protocol_dir(session_id) / "shots.jsonl"


def meta_lock(session_id: str) -> threading.Lock:
    with _meta_locks_guard:
        if session_id not in _meta_locks:
            _meta_locks[session_id] = threading.Lock()
        return _meta_locks[session_id]


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(payload, indent=2, ensure_ascii=False)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    tmp.replace(path)


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def read_json_safe(path: Path) -> dict:
    text = path.read_text(encoding="utf-8")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        decoder = json.JSONDecoder()
        payload, _ = decoder.raw_decode(text.lstrip("\ufeff").strip())
        if isinstance(payload, dict):
            return payload
        return {}


def update_raw_meta(session_id: str, updates: dict) -> None:
    meta_path = raw_meta_path(session_id)
    with meta_lock(session_id):
        meta = read_json_safe(meta_path) if meta_path.exists() else {}
        meta.update(updates)
        write_json(meta_path, meta)


def append_jsonl(path: Path, payload: dict) -> None:
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(payload, ensure_ascii=False) + "\n")


def count_jsonl_lines(path: Path) -> int:
    if not path.exists():
        return 0
    return sum(1 for _ in path.open("r", encoding="utf-8"))
