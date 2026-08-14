"""Advertise the cloud API on the local network via mDNS (ft-cloud.local)."""

from __future__ import annotations

import logging
import socket
import threading
from typing import Callable

logger = logging.getLogger(__name__)

_SERVICE_TYPE = "_http._tcp.local."
_SERVICE_NAME = "FT-Cloud._http._tcp.local."
_zeroconf = None
_info = None
_lock = threading.Lock()


def _lan_ip() -> str | None:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 80))
            return sock.getsockname()[0]
    except OSError:
        return None


def start_mdns(port: int = 8080) -> str | None:
    """Register mDNS service in a background thread. Returns advertised LAN IP if scheduled."""
    ip = _lan_ip()
    if not ip:
        logger.warning("mDNS skipped: no LAN IP")
        return None

    def _register() -> None:
        global _zeroconf, _info
        try:
            from zeroconf import ServiceInfo, Zeroconf
        except ImportError:
            logger.warning("mDNS skipped: zeroconf not installed")
            return

        with _lock:
            stop_mdns()
            try:
                _zeroconf = Zeroconf()
                _info = ServiceInfo(
                    _SERVICE_TYPE,
                    _SERVICE_NAME,
                    addresses=[socket.inet_aton(ip)],
                    port=port,
                    properties={
                        b"path": b"/health",
                        b"app": b"basketball-ft-cloud",
                    },
                    server="ft-cloud.local.",
                )
                _zeroconf.register_service(_info)
                logger.info("mDNS advertised FT-Cloud at %s:%s (ft-cloud.local)", ip, port)
            except Exception as exc:
                logger.warning("mDNS registration failed: %s", exc)
                stop_mdns()

    threading.Thread(target=_register, name="mdns-register", daemon=True).start()
    return ip


def stop_mdns() -> None:
    global _zeroconf, _info
    with _lock:
        if _zeroconf and _info:
            try:
                _zeroconf.unregister_service(_info)
            except Exception:
                pass
        if _zeroconf:
            try:
                _zeroconf.close()
            except Exception:
                pass
        _zeroconf = None
        _info = None


def mdns_hostname(port: int = 8080) -> str:
    return f"http://ft-cloud.local:{port}"
