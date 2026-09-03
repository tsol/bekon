"""Phone-manager HTTP client. Never reads /state.lastSnapshot as the current screen."""
from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from typing import Any

DEFAULT_BASE = "http://127.0.0.1:18082"
EXECUTE_TIMEOUT = 90
QUEUE_TIMEOUT = 15
BUSY_RETRIES = 6
BUSY_SLEEP = 0.4


class PhoneError(Exception):
    pass


def ensure_trailing_snapshot(actions: list[dict]) -> list[dict]:
    """Execute returns snapshot only if the last real command is snapshot (trailing sleep/ping ignored)."""
    kinds = [a.get("kind") for a in actions]
    i = len(kinds) - 1
    while i >= 0 and kinds[i] in ("sleep", "ping"):
        i -= 1
    if i < 0 or kinds[i] != "snapshot":
        return list(actions) + [{"kind": "snapshot"}]
    return list(actions)


class PhoneClient:
    def __init__(self, base: str | None = None, tunnel_id: str | None = None):
        self.base = (base or os.getenv("PHONE_API") or DEFAULT_BASE).rstrip("/")
        env_tunnel = os.getenv("PHONE_TUNNEL") or ""
        self._forced_tunnel = tunnel_id or env_tunnel or None
        self._resolved: str | None = None

    def list_tunnels(self) -> list[dict[str, Any]]:
        data = self._json("GET", "/tunnels", timeout=10)
        if not isinstance(data, list):
            raise PhoneError("GET /tunnels did not return a list")
        return data

    def tunnel_id(self) -> str:
        if self._forced_tunnel:
            return self._forced_tunnel
        if self._resolved:
            return self._resolved
        try:
            tunnels = self.list_tunnels()
        except PhoneError:
            fallback = os.getenv("PHONE_TUNNEL")
            if fallback:
                self._resolved = fallback
                return fallback
            raise
        running = [t for t in tunnels if t.get("running")]
        pick = (running or tunnels)
        if not pick:
            raise PhoneError("no tunnels from phone-control-api")
        self._resolved = str(pick[0]["id"])
        return self._resolved

    def execute(self, actions: list[dict]) -> dict:
        actions = ensure_trailing_snapshot(actions)
        tid = self.tunnel_id()
        self._json("DELETE", f"/tunnels/{tid}/queue", timeout=QUEUE_TIMEOUT)
        self._json("POST", f"/tunnels/{tid}/queue", body=actions, timeout=QUEUE_TIMEOUT)
        last_err: Exception | None = None
        for attempt in range(BUSY_RETRIES):
            try:
                result = self._json(
                    "POST",
                    f"/tunnels/{tid}/queue/execute",
                    timeout=EXECUTE_TIMEOUT,
                )
            except PhoneError as exc:
                if "409" in str(exc) or "busy" in str(exc).lower():
                    last_err = exc
                    time.sleep(BUSY_SLEEP * (attempt + 1))
                    continue
                raise
            if not isinstance(result, dict):
                raise PhoneError("execute did not return JSON object")
            return result
        raise PhoneError(f"phone-control-api busy after {BUSY_RETRIES} retries: {last_err}")

    def snapshot(self, hi_res: bool = True) -> dict:
        tid = self.tunnel_id()
        result = self._json(
            "POST",
            f"/tunnels/{tid}/snapshot",
            body={"hiRes": hi_res},
            timeout=EXECUTE_TIMEOUT,
        )
        if not isinstance(result, dict):
            raise PhoneError("POST /snapshot did not return JSON object")
        return result

    def screenshot_bytes(self) -> bytes:
        tid = self.tunnel_id()
        url = f"{self.base}/tunnels/{tid}/screenshot"
        req = urllib.request.Request(url, method="GET")
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                return resp.read()
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                raise PhoneError("no screenshot yet") from exc
            raise PhoneError(f"GET screenshot HTTP {exc.code}") from exc
        except urllib.error.URLError as exc:
            raise PhoneError(f"GET screenshot failed: {exc}") from exc

    def _json(
        self,
        method: str,
        path: str,
        body: Any = None,
        timeout: int = 30,
    ) -> Any:
        url = f"{self.base}{path}"
        data = None if body is None else json.dumps(body).encode("utf-8")
        headers = {}
        if data is not None:
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read()
                if not raw:
                    return {}
                return json.loads(raw.decode("utf-8"))
        except urllib.error.HTTPError as exc:
            err_body = ""
            try:
                err_body = exc.read().decode("utf-8", errors="replace")[:300]
            except Exception:
                pass
            raise PhoneError(f"{method} {path} HTTP {exc.code}: {err_body or exc.reason}") from exc
        except urllib.error.URLError as exc:
            raise PhoneError(f"{method} {path} failed: {exc}") from exc
        except json.JSONDecodeError as exc:
            raise PhoneError(f"{method} {path} returned non-JSON") from exc
