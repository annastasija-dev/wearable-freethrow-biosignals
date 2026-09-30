import socket
import subprocess
from contextlib import asynccontextmanager
from datetime import datetime, timezone

from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
import io
import zipfile

from .config import settings
from .discovery import mdns_hostname, start_mdns, stop_mdns
from .pain_diary import router as pain_diary_router
from .models import (
    RawCapabilitiesRequest,
    RawImuBatchRequest,
    RawStreamBatchRequest,
    SessionFinishRequest,
    SessionStartRequest,
    SessionStartResponse,
    SessionSummary,
    ShotLabelRequest,
)
from .storage import (
    append_jsonl,
    build_folder_name,
    count_jsonl_lines,
    count_raw_samples,
    ensure_unique_folder_name,
    folder_name_for_session,
    list_raw_stream_files,
    new_session_id,
    protocol_session_path,
    protocol_shots_path,
    raw_capabilities_path,
    raw_imu_path,
    raw_meta_path,
    raw_stream_path,
    read_json,
    read_json_safe,
    session_protocol_dir,
    session_raw_dir,
    update_raw_meta,
    utc_now,
    write_json,
)

try:
    from .sharepoint_sync import sync_session as sharepoint_sync_session
    from .sharepoint_sync import sync_instruction_pack as sharepoint_sync_docs
    from .sharepoint_sync import sync_pain_pack as sharepoint_sync_pain_docs
    from .sharepoint_sync import sync_pain_participant as sharepoint_sync_pain_participant
    from .sharepoint_sync import sync_all_finished as sharepoint_sync_all_finished
except ImportError:
    sharepoint_sync_session = None
    sharepoint_sync_docs = None
    sharepoint_sync_pain_docs = None
    sharepoint_sync_pain_participant = None
    sharepoint_sync_all_finished = None

@asynccontextmanager
async def lifespan(_: FastAPI):
    _finish_stale_recording_sessions(max_age_minutes=45)
    start_mdns(8080)
    yield
    stop_mdns()


def _finish_stale_recording_sessions(max_age_minutes: int = 45) -> int:
    """Close abandoned recording sessions so watch polling picks the right one."""
    if not settings.protocol_root.exists():
        return 0
    cutoff = utc_now().timestamp() - max_age_minutes * 60
    finished = 0
    for path in settings.protocol_root.iterdir():
        if not path.is_dir():
            continue
        session_file = path / "session.json"
        if not session_file.exists():
            continue
        session = read_json(session_file)
        if session.get("status") != "recording":
            continue
        started_raw = session.get("started_at")
        if not started_raw:
            continue
        started_at = datetime.fromisoformat(started_raw)
        if started_at.timestamp() >= cutoff:
            continue
        ended_at = utc_now()
        session["ended_at"] = ended_at.isoformat()
        session["status"] = "finished"
        session["notes"] = (session.get("notes") or "") + " auto-finished (stale)"
        write_json(session_file, session)
        meta_path = raw_meta_path(session["session_id"])
        if meta_path.exists():
            update_raw_meta(
                session["session_id"],
                {
                    "ended_at": ended_at.isoformat(),
                    "status": "finished",
                },
            )
        finished += 1
    return finished


app = FastAPI(
    title="VGTU study cloud",
    version="0.3.0",
    description="Shared server: free-throw sessions and Pain Diary study.",
    lifespan=lifespan,
)
app.include_router(pain_diary_router)

app.add_middleware(
    CORSMiddleware,
    allow_origins=[origin.strip() for origin in settings.cors_origins.split(",")],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


def verify_api_key(x_api_key: str | None = Header(default=None)) -> None:
    if settings.api_key and x_api_key != settings.api_key:
        raise HTTPException(status_code=401, detail="Invalid API key")


def load_protocol(session_id: str) -> dict:
    path = protocol_session_path(session_id)
    if not path.exists():
        raise HTTPException(status_code=404, detail="Session not found")
    return read_json(path)


def _lan_ip() -> str | None:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 80))
            return sock.getsockname()[0]
    except OSError:
        return None


def _all_local_ips() -> list[str]:
    """All IPv4 addresses the phone might reach (LAN + Tailscale)."""
    found: list[str] = []
    primary = _lan_ip()
    if primary:
        found.append(primary)
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip.startswith("127.") or ip in found:
                continue
            found.append(ip)
    except OSError:
        pass
    tailscale = _tailscale_ip()
    if tailscale and tailscale not in found:
        found.append(tailscale)
    return found


def _tailscale_ip() -> str | None:
    try:
        result = subprocess.run(
            ["tailscale", "ip", "-4"],
            capture_output=True,
            text=True,
            timeout=3,
            check=False,
        )
        ip = result.stdout.strip()
        return ip if ip.startswith("100.") else None
    except (OSError, subprocess.TimeoutExpired):
        return None


static_dir = Path(__file__).resolve().parent / "static"
if static_dir.exists():
    app.mount("/static", StaticFiles(directory=static_dir), name="static")


@app.get("/")
@app.get("/install")
def install_page() -> FileResponse:
    return FileResponse(static_dir / "install" / "index.html")


@app.get("/app")
def phone_app() -> FileResponse:
    return FileResponse(static_dir / "app" / "index.html")


@app.get("/install/watch")
def install_watch_page() -> FileResponse:
    """Compact page for Wear browser (RemoteActivity)."""
    return FileResponse(static_dir / "install" / "watch.html")


@app.get("/download/ft-protocol.apk")
def download_phone_apk() -> FileResponse:
    """One-time native app install over Wi-Fi (not the experiment UI)."""
    apk_path = static_dir / "downloads" / "ft-protocol.apk"
    if not apk_path.exists():
        raise HTTPException(status_code=404, detail="APK not built yet")
    return FileResponse(
        apk_path,
        media_type="application/vnd.android.package-archive",
        filename="FT-Protocol-0.5.10.apk",
    )


@app.get("/download/ft-watch.apk")
def download_watch_apk() -> FileResponse:
    """Wear OS companion app for raw biosignal streaming."""
    apk_path = static_dir / "downloads" / "ft-watch.apk"
    if not apk_path.exists():
        raise HTTPException(status_code=404, detail="Watch APK not built yet")
    return FileResponse(
        apk_path,
        media_type="application/vnd.android.package-archive",
        filename="FT-Watch-0.5.2.apk",
    )


@app.get("/download/pain-diary.apk")
def download_pain_phone_apk() -> FileResponse:
    apk_path = static_dir / "downloads" / "pain-diary.apk"
    if not apk_path.exists():
        raise HTTPException(status_code=404, detail="Pain Diary APK not built yet")
    return FileResponse(
        apk_path,
        media_type="application/vnd.android.package-archive",
        filename="PainDiary-0.6.0.apk",
    )


@app.get("/download/pain-watch.apk")
def download_pain_watch_apk() -> FileResponse:
    apk_path = static_dir / "downloads" / "pain-watch.apk"
    if not apk_path.exists():
        raise HTTPException(status_code=404, detail="Pain Diary watch APK not built yet")
    return FileResponse(
        apk_path,
        media_type="application/vnd.android.package-archive",
        filename="PainDiary-Watch-0.4.0.apk",
    )


@app.get("/instructions")
def download_instructions() -> FileResponse:
    path = static_dir / "docs" / "Instructions_EN.txt"
    if not path.exists():
        raise HTTPException(status_code=404, detail="Instructions missing")
    return FileResponse(path, media_type="text/plain; charset=utf-8", filename="Instructions_EN.txt")


@app.get("/instrukcija")
def download_instructions_lt() -> FileResponse:
    path = static_dir / "docs" / "Instrukcija_LT.txt"
    if not path.exists():
        raise HTTPException(status_code=404, detail="Instructions missing")
    return FileResponse(path, media_type="text/plain; charset=utf-8", filename="Instrukcija_LT.txt")


@app.post("/api/v1/sync-docs", dependencies=[Depends(verify_api_key)])
def sync_docs() -> dict:
    if sharepoint_sync_docs is None:
        raise HTTPException(status_code=503, detail="SharePoint sync not available")
    try:
        return sharepoint_sync_docs()
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/api/v1/pain/sync-docs", dependencies=[Depends(verify_api_key)])
def sync_pain_docs() -> dict:
    if sharepoint_sync_pain_docs is None:
        raise HTTPException(status_code=503, detail="SharePoint sync not available")
    try:
        return sharepoint_sync_pain_docs()
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc

@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "raw_root": str(settings.raw_root),
        "protocol_root": str(settings.protocol_root),
    }


@app.get("/api/v1/setup-info")
def setup_info(request: Request) -> dict:
    """Phone install + cloud URLs (no API key required)."""
    host = request.headers.get("host", "localhost:8080")
    scheme = request.url.scheme
    urls: list[str] = []
    for ip in _all_local_ips():
        urls.append(f"http://{ip}:8080")
    urls.append(mdns_hostname(8080))
    if f"{scheme}://{host}" not in urls:
        urls.insert(0, f"{scheme}://{host}")
    unique_urls = list(dict.fromkeys(urls))
    apk_path = static_dir / "downloads" / "ft-protocol.apk"
    watch_apk_path = static_dir / "downloads" / "ft-watch.apk"
    return {
        "status": "ok",
        "api_key": settings.api_key,
        "cloud_urls": unique_urls,
        "install_url": f"{scheme}://{host}/install",
        "apk_url": f"{scheme}://{host}/download/ft-protocol.apk",
        "watch_apk_url": f"{scheme}://{host}/download/ft-watch.apk",
        "apk_ready": apk_path.exists(),
        "watch_apk_ready": watch_apk_path.exists(),
        "apk_bytes": apk_path.stat().st_size if apk_path.exists() else 0,
        "watch_apk_bytes": watch_apk_path.stat().st_size if watch_apk_path.exists() else 0,
    }


def _list_recording_sessions(max_age_minutes: int = 10) -> list[dict]:
    sessions: list[dict] = []
    if not settings.protocol_root.exists():
        return sessions
    cutoff = utc_now().timestamp() - max_age_minutes * 60
    for path in settings.protocol_root.iterdir():
        if not path.is_dir():
            continue
        session_file = path / "session.json"
        if not session_file.exists():
            continue
        session = read_json(session_file)
        if session.get("status") != "recording":
            continue
        started_raw = session.get("started_at")
        if started_raw:
            started_at = datetime.fromisoformat(started_raw)
            if started_at.timestamp() < cutoff:
                continue
        sessions.append(session)
    sessions.sort(key=lambda item: item.get("started_at", ""), reverse=True)
    return sessions


@app.get("/api/v1/sessions", dependencies=[Depends(verify_api_key)])
def list_sessions() -> dict:
    """List all protocol sessions on Fly disk (for recovery / OneDrive re-sync)."""
    items: list[dict] = []
    if not settings.protocol_root.exists():
        return {"count": 0, "sessions": []}
    for path in settings.protocol_root.iterdir():
        if not path.is_dir():
            continue
        session_file = path / "session.json"
        if not session_file.exists():
            continue
        session = read_json(session_file)
        sid = session.get("session_id") or path.name
        raw_count, raw_streams = count_raw_samples(sid)
        items.append(
            {
                "session_id": sid,
                "folder_name": session.get("folder_name") or folder_name_for_session(sid),
                "participant_code": session.get("participant_code"),
                "status": session.get("status"),
                "started_at": session.get("started_at"),
                "ended_at": session.get("ended_at"),
                "shots_recorded": count_jsonl_lines(protocol_shots_path(sid)),
                "raw_samples": raw_count,
                "raw_streams": raw_streams,
            }
        )
    items.sort(key=lambda item: item.get("started_at") or "", reverse=True)
    return {"count": len(items), "sessions": items}


@app.post("/api/v1/sessions/sync-all", dependencies=[Depends(verify_api_key)])
def sync_all_sessions_to_onedrive() -> dict:
    """Re-upload every finished session from Fly disk to OneDrive results/."""
    if sharepoint_sync_all_finished is None:
        raise HTTPException(status_code=503, detail="SharePoint sync not available")
    try:
        results = sharepoint_sync_all_finished()
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    ok = sum(1 for r in results if r.get("status") == "ok")
    return {"count": len(results), "ok": ok, "results": results}


@app.get("/api/v1/sessions/active", dependencies=[Depends(verify_api_key)])
def active_sessions() -> dict:
    """Latest recording session — watch polls this when phone Wi-Fi relay fails."""
    recording = _list_recording_sessions()
    active = recording[0] if recording else None
    return {
        "active": active,
        "sessions": recording,
        "count": len(recording),
    }


@app.post("/api/v1/sessions/start", response_model=SessionStartResponse, dependencies=[Depends(verify_api_key)])
def start_session(body: SessionStartRequest) -> SessionStartResponse:
    started_at = utc_now()
    session_id = new_session_id(body.participant_code, started_at)
    folder_name = ensure_unique_folder_name(
        build_folder_name(body.participant_code, started_at)
    )

    session_payload = {
        "session_id": session_id,
        "folder_name": folder_name,
        "participant_code": body.participant_code,
        "watch_model": body.watch_model,
        "wrist": body.wrist,
        "location": body.location,
        "phone_model": body.phone_model,
        "notes": body.notes,
        "weight_kg": body.weight_kg,
        "height_cm": body.height_cm,
        "age_years": body.age_years,
        "sex": body.sex,
        "skill_level": body.skill_level,
        "throw_technique": body.throw_technique,
        "started_at": started_at.isoformat(),
        "ended_at": None,
        "status": "recording",
        "shots_target": settings.max_shots_per_session,
    }

    write_json(protocol_session_path(session_id), session_payload)
    write_json(
        raw_meta_path(session_id),
        {
            "session_id": session_id,
            "participant_code": body.participant_code,
            "started_at": started_at.isoformat(),
            "ended_at": None,
            "watch_model": body.watch_model,
            "phone_model": body.phone_model,
            "wrist": body.wrist,
            "location": body.location,
            "weight_kg": body.weight_kg,
            "height_cm": body.height_cm,
            "age_years": body.age_years,
            "sex": body.sex,
            "skill_level": body.skill_level,
            "throw_technique": body.throw_technique,
            "source": "samsung_watch_via_phone",
            "format": "jsonl",
            "relay": "wearable_message_api",
            "streams": [],
            "stream_sample_counts": {},
            "sdk_version": "pending",
            "tracker_mode": "pending",
            "status": "recording",
            "note": "Populated after watch sends capabilities + raw batches via FT Watch app",
        },
    )
    session_raw_dir(session_id)
    session_protocol_dir(session_id)

    return SessionStartResponse(
        session_id=session_id,
        folder_name=folder_name,
        started_at=started_at,
        shots_target=settings.max_shots_per_session,
    )


@app.post("/api/v1/sessions/{session_id}/shots", dependencies=[Depends(verify_api_key)])
def label_shot(session_id: str, body: ShotLabelRequest) -> dict:
    session = load_protocol(session_id)
    if session.get("status") == "finished":
        raise HTTPException(status_code=409, detail="Session already finished")

    shots_path = protocol_shots_path(session_id)
    existing = count_jsonl_lines(shots_path)
    if existing >= settings.max_shots_per_session:
        raise HTTPException(status_code=409, detail="All 10 shots already recorded")

    shot_no = body.shot_no if body.shot_no is not None else existing + 1
    if shot_no != existing + 1:
        raise HTTPException(status_code=400, detail=f"Expected shot_no={existing + 1}")

    server_time = utc_now()
    shot_payload = {
        "session_id": session_id,
        "shot_no": shot_no,
        "result": body.result,
        "client_timestamp": body.client_timestamp.astimezone(timezone.utc).isoformat(),
        "server_timestamp": server_time.isoformat(),
    }
    append_jsonl(shots_path, shot_payload)

    return {
        "session_id": session_id,
        "shot_no": shot_no,
        "result": body.result,
        "remaining": settings.max_shots_per_session - shot_no,
        "done": shot_no >= settings.max_shots_per_session,
    }


@app.post("/api/v1/sessions/{session_id}/raw/batch", dependencies=[Depends(verify_api_key)])
def upload_raw_batch(session_id: str, body: RawStreamBatchRequest) -> dict:
    if not protocol_session_path(session_id).exists():
        raise HTTPException(status_code=404, detail="Session not found")

    stream_path = raw_stream_path(session_id, body.stream)
    for sample in body.samples:
        row = dict(sample)
        row.setdefault("stream", body.stream)
        row.setdefault("source", "samsung_health_sensor")
        append_jsonl(stream_path, row)

    total, stream_counts = count_raw_samples(session_id)
    update_raw_meta(
        session_id,
        {
            "streams": sorted(stream_counts.keys()),
            "stream_sample_counts": stream_counts,
        },
    )

    return {
        "session_id": session_id,
        "stream": body.stream,
        "accepted": len(body.samples),
        "total_samples": stream_counts.get(body.stream, count_jsonl_lines(stream_path)),
        "raw_streams": stream_counts,
        "raw_samples_total": total,
    }


@app.post("/api/v1/sessions/{session_id}/raw/capabilities", dependencies=[Depends(verify_api_key)])
def upload_raw_capabilities(session_id: str, body: RawCapabilitiesRequest) -> dict:
    if not protocol_session_path(session_id).exists():
        raise HTTPException(status_code=404, detail="Session not found")

    payload = body.model_dump()
    payload["session_id"] = session_id
    payload["received_at"] = utc_now().isoformat()
    write_json(raw_capabilities_path(session_id), payload)

    update_raw_meta(
        session_id,
        {
            "watch_model": body.watch_model,
            "sdk_version": body.sdk_version,
            "tracker_mode": body.tracker_mode,
            "streams": sorted(body.active_streams or body.supported_streams),
            "supported_streams": sorted(body.supported_streams),
            "active_streams": sorted(body.active_streams),
        },
    )

    return {
        "session_id": session_id,
        "active_streams": body.active_streams,
        "supported_streams": body.supported_streams,
    }


@app.post("/api/v1/sessions/{session_id}/finish", response_model=SessionSummary, dependencies=[Depends(verify_api_key)])
def finish_session(session_id: str, body: SessionFinishRequest) -> SessionSummary:
    session = load_protocol(session_id)
    already_finished = session.get("status") == "finished"
    ended_at = (
        datetime.fromisoformat(session["ended_at"])
        if already_finished and session.get("ended_at")
        else (body.ended_at.astimezone(timezone.utc) if body.ended_at else utc_now())
    )

    if not already_finished:
        session["ended_at"] = ended_at.isoformat()
        session["status"] = "finished"
        if body.notes:
            session["notes"] = body.notes
        write_json(protocol_session_path(session_id), session)

    shots = count_jsonl_lines(protocol_shots_path(session_id))
    raw_count, raw_streams = count_raw_samples(session_id)
    update_raw_meta(
        session_id,
        {
            "ended_at": ended_at.isoformat(),
            "status": "finished",
            "shots_recorded": shots,
            "raw_samples_total": raw_count,
            "streams": sorted(raw_streams.keys()),
            "stream_sample_counts": raw_streams,
        },
    )

    primary_raw = raw_stream_path(session_id, "accelerometer")
    if not primary_raw.exists():
        first_stream = list_raw_stream_files(session_id)
        primary_raw = first_stream[0] if first_stream else raw_imu_path(session_id)

    sp_status: str | None = None
    sp_path: str | None = None
    if sharepoint_sync_session is not None:
        try:
            sp_result = sharepoint_sync_session(session_id)
            sp_status = sp_result.get("status")
            sp_path = sp_result.get("sharepoint_url") or (
                sp_result.get("uploaded", [None])[0] if sp_result.get("uploaded") else None
            )
        except Exception as exc:
            sp_status = f"error: {exc}"

    return SessionSummary(
        session_id=session_id,
        folder_name=folder_name_for_session(session_id),
        participant_code=session["participant_code"],
        started_at=datetime.fromisoformat(session["started_at"]),
        ended_at=ended_at,
        shots_recorded=shots,
        raw_samples=raw_count,
        raw_streams=raw_streams,
        raw_file=str(primary_raw),
        protocol_file=str(protocol_session_path(session_id)),
        sharepoint_sync=sp_status,
        sharepoint_path=sp_path,
    )


@app.post("/api/v1/sessions/{session_id}/sync-sharepoint", dependencies=[Depends(verify_api_key)])
def sync_session_sharepoint(session_id: str) -> dict:
    if not protocol_session_path(session_id).exists():
        raise HTTPException(status_code=404, detail="Session not found")
    if sharepoint_sync_session is None:
        raise HTTPException(status_code=503, detail="SharePoint sync not available")
    try:
        return sharepoint_sync_session(session_id)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.get("/api/v1/sessions/{session_id}/download", dependencies=[Depends(verify_api_key)])
def download_session_zip(session_id: str) -> StreamingResponse:
    """Download protocol + raw files as a zip (recovery when OneDrive sync fails)."""
    if not protocol_session_path(session_id).exists():
        raise HTTPException(status_code=404, detail="Session not found")
    folder = folder_name_for_session(session_id)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for path in session_protocol_dir(session_id).iterdir():
            if path.is_file():
                zf.write(path, f"{folder}/protocol/{path.name}")
        raw_dir = session_raw_dir(session_id)
        if raw_dir.exists():
            for path in raw_dir.iterdir():
                if path.is_file():
                    zf.write(path, f"{folder}/raw/{path.name}")
    buf.seek(0)
    return StreamingResponse(
        buf,
        media_type="application/zip",
        headers={"Content-Disposition": f'attachment; filename="{folder}.zip"'},
    )


@app.get("/api/v1/sessions/{session_id}", response_model=SessionSummary, dependencies=[Depends(verify_api_key)])
def get_session(session_id: str) -> SessionSummary:
    session = load_protocol(session_id)
    ended_at = datetime.fromisoformat(session["ended_at"]) if session.get("ended_at") else None
    raw_count, raw_streams = count_raw_samples(session_id)
    primary_raw = raw_stream_path(session_id, "accelerometer")
    if not primary_raw.exists():
        first_stream = list_raw_stream_files(session_id)
        primary_raw = first_stream[0] if first_stream else raw_imu_path(session_id)
    return SessionSummary(
        session_id=session_id,
        folder_name=folder_name_for_session(session_id),
        participant_code=session["participant_code"],
        started_at=datetime.fromisoformat(session["started_at"]),
        ended_at=ended_at,
        shots_recorded=count_jsonl_lines(protocol_shots_path(session_id)),
        raw_samples=raw_count,
        raw_streams=raw_streams,
        raw_file=str(primary_raw),
        protocol_file=str(protocol_session_path(session_id)),
    )
