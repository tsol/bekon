"""Compact a11y look: chrome strip, regions, screenshot save, swipe guards."""
from __future__ import annotations

import os
from datetime import datetime
from pathlib import Path
from typing import Iterable

HOME_SCREEN_CLUES = ("Поиск в Google", "Поиск приложений", "Голосовой поиск")
DEFAULT_SCREEN_W = 480
DEFAULT_SCREEN_H = 960
CHROME_STATUS_FRAC = 50 / 960
CHROME_NAV_FRAC = 900 / 960
MAX_SHOTS = 50
DEFAULT_SHOT_DIR = "/tmp/phone-mcp"

# Madison: JPEG size as a proxy for screen state
STALE_BYTES = 150_000
OVERLAY_BYTES = 200_000
FRESH_BYTES = 280_000


class GuardError(Exception):
    pass


def workspace_host() -> Path | None:
    """Host workspace root for screenshot path mapping."""
    env = os.getenv("PHONE_WORKSPACE_HOST")
    if env:
        return Path(env).expanduser().resolve()
    here = Path(__file__).resolve()
    if len(here.parents) > 4:
        cand = here.parents[4]
        if (cand / "projects").is_dir():
            return cand
    return None


def hermes_visible_path(path: str) -> str:
    """Rewrite a host screenshot path for agents when a container prefix is configured."""
    if not path:
        return path
    raw = Path(path)
    try:
        resolved = raw.resolve()
    except OSError:
        resolved = raw
    s = str(resolved)
    hermes_root = os.getenv("PHONE_WORKSPACE_HERMES")
    if not hermes_root:
        return str(raw)
    if s == hermes_root or s.startswith(hermes_root + os.sep):
        return s
    host = workspace_host()
    if host is None:
        return str(raw)
    try:
        rel = resolved.relative_to(host)
    except ValueError:
        return str(raw)
    return str(Path(hermes_root) / rel)


def screen_size(snap: dict | None) -> tuple[int, int]:
    snap = snap or {}
    w = int(snap.get("screenW") or DEFAULT_SCREEN_W)
    h = int(snap.get("screenH") or DEFAULT_SCREEN_H)
    return max(w, 1), max(h, 1)


def classify_region(y: int, screen_h: int = DEFAULT_SCREEN_H) -> str:
    """Y bands from the 480x960 launcher layout, scaled to screen_h."""
    h = max(int(screen_h) or DEFAULT_SCREEN_H, 1)
    if y < h * 50 / 960:
        return "status_bar"
    if y < h * 150 / 960:
        return "search_bar"
    if y < h * 780 / 960:
        return "app_grid"
    if y < h * 800 / 960:
        return "page_dots"
    if y < h * 900 / 960:
        return "dock"
    return "nav_bar"


def is_chrome(item: dict, screen_h: int = DEFAULT_SCREEN_H) -> bool:
    y = int(item.get("y") or 0)
    return classify_region(y, screen_h) in ("status_bar", "nav_bar")


def strip_chrome(items: Iterable[dict], screen_h: int = DEFAULT_SCREEN_H) -> list[dict]:
    return [it for it in items if not is_chrome(it, screen_h)]


def find_label(items: Iterable[dict], keyword: str) -> dict | None:
    kw = keyword.lower()
    for item in items:
        if kw in (item.get("name") or "").lower():
            return item
    return None


def is_home_screen(items: Iterable[dict]) -> bool:
    """True only on the launcher. 'Главный экран' is the nav-bar button inside apps too."""
    return any(find_label(items, clue) for clue in HOME_SCREEN_CLUES)


def find_app_icon(
    items: Iterable[dict],
    app_name: str,
    region: str | None = None,
    screen_h: int = DEFAULT_SCREEN_H,
) -> dict | None:
    """Find by substring. Skip status-bar notifications and huge names."""
    kw = app_name.lower()
    for item in items:
        name = item.get("name") or ""
        y = int(item.get("y") or 0)
        if len(name) > 40 or classify_region(y, screen_h) == "status_bar":
            continue
        if region and classify_region(y, screen_h) != region:
            continue
        if kw in name.lower():
            return item
    return None


def find_by_ref(items: Iterable[dict], ref: str) -> dict | None:
    for item in items:
        if item.get("ref") == ref:
            return item
    return None


def shot_hint(nbytes: int) -> tuple[str, str]:
    if nbytes <= 0:
        return "missing", "no screenshot file"
    if nbytes < STALE_BYTES:
        return "stale", "screenshot looks stale — do not trust labels; call look() again"
    if nbytes < OVERLAY_BYTES:
        return "overlay", "possible floating dialog; taps may miss — use act(kind=nav, nav=back)"
    if nbytes >= FRESH_BYTES:
        return "fresh", "screenshot looks fresh"
    return "ok", ""


def format_look(
    snap: dict | None,
    items: list[dict],
    shot_path: str,
    shot_size: int,
    include_chrome: bool = False,
    extra: str = "",
) -> str:
    snap = snap or {}
    w, h = screen_size(snap)
    visible = items if include_chrome else strip_chrome(items, h)
    home = is_home_screen(items)
    kind, hint = shot_hint(shot_size)
    overlay = "possible" if kind == "overlay" else "no"
    lines = [
        f"screen: {w}x{h}  home: {str(home).lower()}  overlay: {overlay}  shot: {shot_size // 1024}KB",
        f"screenshot: {hermes_visible_path(shot_path) if shot_path else '(none)'}",
    ]
    if hint:
        lines.append(f"hint: {hint}")
    if extra:
        lines.append(extra)
    if not visible:
        lines.append("items: (none)")
        return "\n".join(lines)
    lines.append("items:")
    for it in visible:
        name = (it.get("name") or "").replace("\n", " ")[:60]
        ref = it.get("ref") or "?"
        x = int(it.get("x") or 0)
        y = int(it.get("y") or 0)
        src = it.get("source") or ""
        lines.append(f"  [{ref}] {name}  ({x},{y}) {classify_region(y, h)} {src}".rstrip())
    return "\n".join(lines)


def save_shot(jpeg: bytes, shot_dir: str | None = None, keep: int = MAX_SHOTS) -> str:
    if not jpeg:
        return ""
    directory = Path(shot_dir or os.getenv("PHONE_SHOTS") or DEFAULT_SHOT_DIR)
    directory.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    path = directory / f"{stamp}.jpg"
    n = 0
    while path.exists():
        n += 1
        path = directory / f"{stamp}-{n}.jpg"
    path.write_bytes(jpeg)
    rotate_shots(directory, keep=keep)
    return str(path)


def rotate_shots(directory: Path, keep: int = MAX_SHOTS) -> None:
    files = sorted(directory.glob("*.jpg"), key=lambda p: p.stat().st_mtime)
    for old in files[:-keep]:
        try:
            old.unlink()
        except OSError:
            pass


def swipe_is_vertical(x1: int, y1: int, x2: int, y2: int, min_delta: int = 40) -> bool:
    dx, dy = abs(x2 - x1), abs(y2 - y1)
    return dy > dx and dy >= min_delta


def reject_bad_swipe(x1: int, y1: int, x2: int, y2: int, on_home: bool) -> None:
    if abs(x2 - x1) < 5 and abs(y2 - y1) < 5:
        raise GuardError(
            "zero-distance swipe hangs the phone queue; use act(kind=long_press) instead"
        )
    if on_home and swipe_is_vertical(x1, y1, x2, y2):
        raise GuardError(
            "vertical swipe on the home screen opens the app drawer; use open() to find an app"
        )


def page_swipe_coords(direction: str, screen_w: int, screen_h: int) -> tuple[int, int, int, int]:
    """Horizontal page turn at ~y=500 on a 960-tall screen."""
    y = int(screen_h * 500 / 960)
    left_x = int(screen_w * 40 / 480)
    right_x = int(screen_w * 400 / 480)
    if direction == "left":
        return right_x, y, left_x, y
    if direction == "right":
        return left_x, y, right_x, y
    raise GuardError(f"page swipe direction must be left or right, got {direction!r}")


def content_swipe_coords(direction: str, screen_w: int, screen_h: int) -> tuple[int, int, int, int]:
    """Finger motion: down = swipe up (content moves down), matching phone-scroll.py."""
    x = screen_w // 2
    high = int(screen_h * 200 / 960)
    low = int(screen_h * 600 / 960)
    if direction in ("down", "up"):
        # 'down' means scroll content down = finger moves up
        if direction == "down":
            return x, low, x, high
        return x, high, x, low
    raise GuardError(f"content swipe direction must be up or down, got {direction!r}")
