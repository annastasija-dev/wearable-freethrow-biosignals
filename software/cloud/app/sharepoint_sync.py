"""Upload session data to SharePoint / OneDrive via Microsoft Graph API."""

from __future__ import annotations

import json
import logging
from pathlib import Path

import httpx
import msal

from .config import settings
from .storage import (
    folder_name_for_session,
    list_raw_stream_files,
    protocol_session_path,
    protocol_shots_path,
    raw_capabilities_path,
    raw_meta_path,
    raw_stream_path,
    session_raw_dir,
)

logger = logging.getLogger(__name__)

GRAPH_SCOPE = ["Files.ReadWrite"]
GRAPH_BASE = "https://graph.microsoft.com/v1.0"


def _authority() -> str:
    return f"https://login.microsoftonline.com/{settings.graph_tenant_id}"


def _token_cache_path() -> Path:
    return settings.graph_token_cache


def _load_cache() -> msal.SerializableTokenCache:
    cache = msal.SerializableTokenCache()
    path = _token_cache_path()
    if path.exists():
        cache.deserialize(path.read_text(encoding="utf-8"))
    return cache


def _save_cache(cache: msal.SerializableTokenCache) -> None:
    if cache.has_state_changed:
        path = _token_cache_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(cache.serialize(), encoding="utf-8")


def _msal_app() -> msal.PublicClientApplication:
    return msal.PublicClientApplication(
        settings.graph_client_id,
        authority=_authority(),
        token_cache=_load_cache(),
    )


def acquire_token_interactive() -> dict:
    """Device-code login — run once via sharepoint_login.py."""
    app = _msal_app()
    accounts = app.get_accounts()
    if accounts:
        result = app.acquire_token_silent(GRAPH_SCOPE, account=accounts[0])
        if result and "access_token" in result:
            _save_cache(app.token_cache)
            return result

    flow = app.initiate_device_flow(scopes=GRAPH_SCOPE)
    if "user_code" not in flow:
        raise RuntimeError(f"Device flow failed: {flow}")

    print(flow["message"])
    result = app.acquire_token_by_device_flow(flow)
    _save_cache(app.token_cache)

    if "access_token" not in result:
        raise RuntimeError(result.get("error_description", "Login failed"))
    return result


def _get_access_token() -> str:
    app = _msal_app()
    if not settings.graph_client_id:
        raise RuntimeError("GRAPH_CLIENT_ID not set")

    # Prefer silent refresh from cached account
    accounts = app.get_accounts()
    if accounts:
        result = app.acquire_token_silent(GRAPH_SCOPE, account=accounts[0])
        if result and "access_token" in result:
            _save_cache(app.token_cache)
            return result["access_token"]

    # Always-on hosts: refresh token from env (set once after local login)
    refresh = (settings.graph_refresh_token or "").strip()
    if refresh:
        result = app.acquire_token_by_refresh_token(refresh, scopes=GRAPH_SCOPE)
        _save_cache(app.token_cache)
        if result and "access_token" in result:
            return result["access_token"]
        raise RuntimeError(
            result.get("error_description", "Refresh token login failed")
        )

    raise RuntimeError(
        "OneDrive not logged in. Run sharepoint_login.py once, then set "
        "GRAPH_REFRESH_TOKEN (or copy .token_cache.json) on the server."
    )


_SIMPLE_UPLOAD_MAX = 4 * 1024 * 1024
_UPLOAD_CHUNK = 320 * 1024 * 10  # Graph requires a multiple of 320 KiB


def _upload_file(token: str, remote_path: str, local_path: Path) -> None:
    if not local_path.exists():
        return
    size = local_path.stat().st_size
    if size > _SIMPLE_UPLOAD_MAX:
        _upload_file_chunked(token, remote_path, local_path)
        return
    url = f"{GRAPH_BASE}/me/drive/root:/{remote_path}:/content"
    content = local_path.read_bytes()
    response = httpx.put(
        url,
        headers={"Authorization": f"Bearer {token}"},
        content=content,
        timeout=120.0,
    )
    if response.status_code not in (200, 201):
        raise RuntimeError(
            f"Upload failed {local_path.name}: {response.status_code} {response.text[:300]}"
        )


def _upload_file_chunked(token: str, remote_path: str, local_path: Path) -> None:
    session_url = f"{GRAPH_BASE}/me/drive/root:/{remote_path}:/createUploadSession"
    started = httpx.post(
        session_url,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        },
        json={"item": {"@microsoft.graph.conflictBehavior": "replace", "name": Path(remote_path).name}},
        timeout=60.0,
    )
    if started.status_code not in (200, 201):
        raise RuntimeError(
            f"Upload session failed {Path(remote_path).name}: {started.status_code} {started.text[:300]}"
        )
    upload_url = started.json()["uploadUrl"]
    size = local_path.stat().st_size
    start = 0
    with local_path.open("rb") as handle:
        while start < size:
            data = handle.read(_UPLOAD_CHUNK)
            end = start + len(data) - 1
            put = httpx.put(
                upload_url,
                headers={
                    "Content-Length": str(len(data)),
                    "Content-Range": f"bytes {start}-{end}/{size}",
                },
                content=data,
                timeout=180.0,
            )
            if put.status_code not in (200, 201, 202):
                raise RuntimeError(
                    f"Chunk upload failed {local_path.name}: {put.status_code} {put.text[:300]}"
                )
            start = end + 1


def _delete_file(token: str, remote_path: str) -> bool:
    url = f"{GRAPH_BASE}/me/drive/root:/{remote_path}"
    response = httpx.delete(
        url,
        headers={"Authorization": f"Bearer {token}"},
        timeout=60.0,
    )
    return response.status_code in (200, 204, 404)


def _copy_local(session_id: str) -> str | None:
    """Copy to local OneDrive sync folder if configured."""
    base = settings.sharepoint_local_path
    if not base:
        return None
    label = folder_name_for_session(session_id)
    dest = base / label
    dest.mkdir(parents=True, exist_ok=True)

    files = [
        (protocol_session_path(session_id), dest / "protocol" / "session.json"),
        (protocol_shots_path(session_id), dest / "protocol" / "shots.jsonl"),
        (raw_meta_path(session_id), dest / "raw" / "meta.json"),
        (raw_capabilities_path(session_id), dest / "raw" / "capabilities.json"),
    ]
    for stream_file in list_raw_stream_files(session_id):
        files.append((stream_file, dest / "raw" / stream_file.name))
    for src, dst in files:
        if src.exists():
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_bytes(src.read_bytes())
    return str(dest)


def sync_session(session_id: str) -> dict:
    """Sync one session to SharePoint folder + optional local mirror."""
    folder = settings.sharepoint_folder.strip("/\\")
    label = folder_name_for_session(session_id)
    remote_base = f"{folder}/{label}"
    uploaded: list[str] = []

    local_dest = _copy_local(session_id)
    if local_dest:
        uploaded.append(f"local:{local_dest}")

    if not settings.sharepoint_enabled:
        return {
            "status": "skipped",
            "reason": "sharepoint_enabled=false",
            "uploaded": uploaded,
        }

    if not settings.graph_client_id:
        if local_dest:
            return {"status": "ok", "mode": "local_only", "uploaded": uploaded}
        return {
            "status": "skipped",
            "reason": "GRAPH_CLIENT_ID not set",
            "uploaded": [],
        }

    token = _get_access_token()
    file_map = [
        (protocol_session_path(session_id), f"{remote_base}/protocol/session.json"),
        (protocol_shots_path(session_id), f"{remote_base}/protocol/shots.jsonl"),
        (raw_meta_path(session_id), f"{remote_base}/raw/meta.json"),
        (raw_capabilities_path(session_id), f"{remote_base}/raw/capabilities.json"),
    ]
    for stream_file in list_raw_stream_files(session_id):
        file_map.append((stream_file, f"{remote_base}/raw/{stream_file.name}"))
    for local_path, remote_path in file_map:
        if local_path.exists():
            _upload_file(token, remote_path, local_path)
            uploaded.append(remote_path)

    manifest = {
        "session_id": session_id,
        "folder_name": label,
        "sharepoint_folder": folder,
        "files": uploaded,
    }
    manifest_path = settings.data_root / "sync_manifests" / f"{session_id}.json"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    return {
        "status": "ok",
        "mode": "graph",
        "remote_base": remote_base,
        "uploaded": uploaded,
        "sharepoint_url": (
            f"https://vgtuitsc-my.sharepoint.com/personal/"
            f"anastasija_grubinskiene_vilniustech_lt/Documents/{folder}/{label}"
        ),
    }


def sync_all_finished() -> list[dict]:
    results = []
    for path in settings.protocol_root.iterdir():
        if not path.is_dir():
            continue
        session_file = path / "session.json"
        if not session_file.exists():
            continue
        data = json.loads(session_file.read_text(encoding="utf-8"))
        if data.get("status") != "finished":
            continue
        sid = data["session_id"]
        try:
            results.append({"session_id": sid, **sync_session(sid)})
        except Exception as exc:
            results.append({"session_id": sid, "status": "error", "error": str(exc)})
    return results


def instruction_pack_root() -> str:
    folder = settings.sharepoint_folder.strip("/\\")
    if folder.lower().endswith("/results"):
        return folder[: -len("/results")]
    return folder


def sync_instruction_pack() -> dict:
    """Upload protocol files to the OneDrive pack folder (not into results/)."""
    docs = Path(__file__).resolve().parent / "static" / "docs"
    root = instruction_pack_root()
    downloads = Path(__file__).resolve().parent / "static" / "downloads"
    mapping = [
        (docs / "Instructions_EN.txt", f"{root}/Instructions_EN.txt"),
        (docs / "Instrukcija_LT.txt", f"{root}/Instrukcija_LT.txt"),
        (docs / "Overleaf_GitHub.txt", f"{root}/Overleaf_GitHub.txt"),
        (docs / "results_README.txt", f"{root}/results/README_LT.txt"),
        (docs / "results_README_EN.txt", f"{root}/results/README_EN.txt"),
        (downloads / "ft-protocol.apk", f"{root}/phone/FT-Protocol-0.5.10.apk"),
        (downloads / "ft-watch.apk", f"{root}/watch/FT-Watch-0.5.2.apk"),
    ]
    if not settings.sharepoint_enabled or not settings.graph_client_id:
        return {"status": "skipped", "reason": "sharepoint not configured"}
    token = _get_access_token()
    uploaded: list[str] = []
    for local_path, remote_path in mapping:
        if not local_path.exists():
            continue
        _upload_file(token, remote_path, local_path)
        uploaded.append(remote_path)
    deleted: list[str] = []
    for extra in (
        "README.txt",
        "README_LT.txt",
        "README_EN.txt",
        "Straipsnis.txt",
        "phone/FT-Protocol.apk",
        "phone/FT-Protocol-0.5.9.apk",
        "watch/FT-Watch.apk",
    ):
        remote = f"{root}/{extra}"
        if _delete_file(token, remote):
            deleted.append(remote)
    old_results_readme = f"{root}/results/README.txt"
    if _delete_file(token, old_results_readme):
        deleted.append(old_results_readme)
    return {
        "status": "ok",
        "folder": root,
        "uploaded": uploaded,
        "deleted": deleted,
        "sharepoint_url": (
            "https://vgtuitsc-my.sharepoint.com/personal/"
            "anastasija_grubinskiene_vilniustech_lt/Documents/" + root
        ),
    }


def pain_pack_root() -> str:
    return settings.sharepoint_pain_folder.strip("/\\")


def sync_pain_pack() -> dict:
    """Create OneDrive folder Skausmo dienorasciu duomenys and upload pack files."""
    docs = Path(__file__).resolve().parent / "static" / "pain-docs"
    downloads = Path(__file__).resolve().parent / "static" / "downloads"
    root = pain_pack_root()
    mapping = [
        (docs / "Instrukcija_LT.txt", f"{root}/Instrukcija_LT.txt"),
        (docs / "Instructions_EN.txt", f"{root}/Instructions_EN.txt"),
        (docs / "results_README_LT.txt", f"{root}/results/README_LT.txt"),
        (docs / "results_README_EN.txt", f"{root}/results/README_EN.txt"),
        (docs / "Straipsnis_logika_LT.txt", f"{root}/paper/Straipsnis_logika_LT.txt"),
        (docs / "Article_outline_EN.txt", f"{root}/paper/Article_outline_EN.txt"),
        (docs / "README_etika.txt", f"{root}/etika/README_etika.txt"),
        (docs / "Informacija_dalyviui_LT.txt", f"{root}/etika/Informacija_dalyviui_LT.txt"),
        (docs / "Sutikimas_LT.txt", f"{root}/etika/Sutikimas_LT.txt"),
        (docs / "Etikos_paraiska_LT.txt", f"{root}/etika/Etikos_paraiska_LT.txt"),
        (docs / "Tyrimo_protokolas_etikai_LT.txt", f"{root}/etika/Tyrimo_protokolas_etikai_LT.txt"),
        (docs / "Participant_information_EN.txt", f"{root}/etika/Participant_information_EN.txt"),
        (docs / "Consent_EN.txt", f"{root}/etika/Consent_EN.txt"),
        (downloads / "pain-diary.apk", f"{root}/phone/PainDiary-0.9.0.apk"),
        (downloads / "pain-watch.apk", f"{root}/watch/PainDiary-Watch-0.4.0.apk"),
    ]
    if not settings.sharepoint_enabled or not settings.graph_client_id:
        return {"status": "skipped", "reason": "sharepoint not configured", "folder": root}
    token = _get_access_token()
    uploaded: list[str] = []
    for local_path, remote_path in mapping:
        if not local_path.exists():
            continue
        _upload_file(token, remote_path, local_path)
        uploaded.append(remote_path)
    return {
        "status": "ok",
        "folder": root,
        "uploaded": uploaded,
        "sharepoint_url": (
            "https://vgtuitsc-my.sharepoint.com/personal/"
            "anastasija_grubinskiene_vilniustech_lt/Documents/" + root
        ),
    }


def sync_pain_participant(code: str) -> dict:
    """Upload one Pain Diary participant to OneDrive results/{CODE}/."""
    root = pain_pack_root()
    participant = code.strip().upper()
    local_root = settings.data_root / "pain" / participant
    if not local_root.exists():
        return {"status": "skipped", "reason": "no local pain data", "code": participant}
    if not settings.sharepoint_enabled or not settings.graph_client_id:
        return {"status": "skipped", "reason": "sharepoint not configured"}
    token = _get_access_token()
    remote_base = f"{root}/results/{participant}"
    uploaded: list[str] = []
    profile = local_root / "profile.json"
    protocol = local_root / "protocol.json"
    events = local_root / "events.jsonl"
    if profile.exists():
        _upload_file(token, f"{remote_base}/profile.json", profile)
        uploaded.append(f"{remote_base}/profile.json")
    if protocol.exists():
        _upload_file(token, f"{remote_base}/protocol.json", protocol)
        uploaded.append(f"{remote_base}/protocol.json")
    if events.exists():
        _upload_file(token, f"{remote_base}/events.jsonl", events)
        uploaded.append(f"{remote_base}/events.jsonl")
    raw_root = local_root / "raw"
    if raw_root.exists():
        for path in raw_root.rglob("*.jsonl"):
            rel = path.relative_to(raw_root).as_posix()
            remote = f"{remote_base}/raw/{rel}"
            _upload_file(token, remote, path)
            uploaded.append(remote)
    return {
        "status": "ok",
        "code": participant,
        "uploaded": uploaded,
        "sharepoint_url": (
            "https://vgtuitsc-my.sharepoint.com/personal/"
            "anastasija_grubinskiene_vilniustech_lt/Documents/"
            f"{remote_base}"
        ),
    }
