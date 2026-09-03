"""High-level phone ops: look, open, find, act, reset. No wake taps — APK wakes itself."""
from __future__ import annotations

import threading
from typing import Any

from client import PhoneClient, PhoneError
from look import (
    GuardError,
    content_swipe_coords,
    find_app_icon,
    find_by_ref,
    find_label,
    format_look,
    is_home_screen,
    page_swipe_coords,
    reject_bad_swipe,
    save_shot,
    screen_size,
    strip_chrome,
)

APP_OPEN_SLEEP_MS = 5000
PAGE_SWIPE_SLEEP_MS = 800
NAV_SLEEP_MS = 600
MAX_SWIPES_DEFAULT = 4
SCROLL_SWIPES_DEFAULT = 6


class Phone:
    def __init__(self, client: PhoneClient | None = None):
        self.client = client or PhoneClient()
        self._lock = threading.Lock()
        self.last_items: list[dict] = []
        self.last_snap: dict = {}
        self.last_shot_path = ""
        self.last_shot_size = 0
        self.include_chrome_default = False

    def _run(self, fn, *args, **kwargs) -> str:
        with self._lock:
            try:
                return fn(*args, **kwargs)
            except (GuardError, PhoneError) as exc:
                return f"ERROR: {exc}"
            except Exception as exc:
                return f"ERROR: {type(exc).__name__}: {exc}"

    def look(self, include_chrome: bool = False) -> str:
        return self._run(self._look, include_chrome)

    def open(self, name: str, max_swipes: int = MAX_SWIPES_DEFAULT) -> str:
        return self._run(self._open, name, max_swipes)

    def find(self, query: str, tap: bool = False, scan: str = "screen") -> str:
        return self._run(self._find, query, tap, scan)

    def act(
        self,
        kind: str,
        ref: str | None = None,
        text: str | None = None,
        x: int | None = None,
        y: int | None = None,
        x1: int | None = None,
        y1: int | None = None,
        x2: int | None = None,
        y2: int | None = None,
        direction: str | None = None,
        nav: str | None = None,
        submit: bool = False,
        from_ref: str | None = None,
        to_ref: str | None = None,
    ) -> str:
        return self._run(
            self._act,
            kind=kind,
            ref=ref,
            text=text,
            x=x,
            y=y,
            x1=x1,
            y1=y1,
            x2=x2,
            y2=y2,
            direction=direction,
            nav=nav,
            submit=submit,
            from_ref=from_ref,
            to_ref=to_ref,
        )

    def reset(self) -> str:
        return self._run(self._reset)

    def see(self, question: str) -> str:
        def _see() -> str:
            body = self._look(False) if not self.last_shot_path else format_look(
                self.last_snap,
                self.last_items,
                self.last_shot_path,
                self.last_shot_size,
                include_chrome=False,
            )
            return (
                f"question: {question}\n"
                f"vision: feed screenshot path to Hermes vision; this tool does not call a VL model\n"
                f"{body}"
            )
        return self._run(_see)

    def _look(self, include_chrome: bool) -> str:
        result = self.client.snapshot()
        return self._commit(result, include_chrome)

    def _go_home(self) -> list[dict]:
        result = self.client.execute([
            {"kind": "nav", "nav": "home"}, {"kind": "sleep", "ms": NAV_SLEEP_MS},
            {"kind": "nav", "nav": "home"}, {"kind": "sleep", "ms": 1200},
        ])
        self._commit(result, False, save=False)
        items = self.last_items
        if not is_home_screen(items):
            result = self.client.execute([
                {"kind": "nav", "nav": "home"}, {"kind": "sleep", "ms": 1000},
            ])
            self._commit(result, False, save=False)
        return self.last_items

    def _open(self, name: str, max_swipes: int) -> str:
        if not (name or "").strip():
            raise PhoneError("open() needs an app name")
        icon, page = self._find_on_home_pages(name.strip(), max_swipes)
        if not icon:
            self._persist_shot()
            extra = f"ERROR: '{name}' not found after {max_swipes} page swipes each way"
            return format_look(
                self.last_snap, self.last_items, self.last_shot_path,
                self.last_shot_size, False, extra,
            )
        result = self.client.execute([
            {"kind": "tap", "x": icon["x"], "y": icon["y"]},
            {"kind": "sleep", "ms": APP_OPEN_SLEEP_MS},
        ])
        extra = f"opened: {icon.get('name')} at ({icon['x']},{icon['y']}) page_offset={page}"
        return self._commit(result, False, extra)

    def _find(self, query: str, tap: bool, scan: str) -> str:
        query = (query or "").strip()
        if not query:
            raise PhoneError("find() needs a query")
        scan = (scan or "screen").strip().lower()
        if scan not in ("screen", "home_pages", "scroll"):
            raise PhoneError("scan must be screen, home_pages, or scroll")

        if scan == "home_pages":
            icon, page = self._find_on_home_pages(query, MAX_SWIPES_DEFAULT)
            if not icon:
                self._persist_shot()
                extra = f"ERROR: '{query}' not found on home pages"
                return format_look(
                    self.last_snap, self.last_items, self.last_shot_path,
                    self.last_shot_size, False, extra,
                )
            extra = f"found: {icon.get('name')} at ({icon['x']},{icon['y']}) page_offset={page}"
            if tap:
                result = self.client.execute([
                    {"kind": "tap", "x": icon["x"], "y": icon["y"]},
                    {"kind": "sleep", "ms": APP_OPEN_SLEEP_MS},
                ])
                return self._commit(result, False, extra)
            self._persist_shot()
            return format_look(
                self.last_snap, self.last_items, self.last_shot_path,
                self.last_shot_size, False, extra,
            )

        if scan == "scroll":
            return self._find_scroll(query, tap)

        if not self.last_items:
            self._look(False)
        w, h = screen_size(self.last_snap)
        item = find_app_icon(self.last_items, query, screen_h=h) or find_label(
            strip_chrome(self.last_items, h), query
        )
        if not item:
            extra = f"ERROR: '{query}' not on current screen"
            return format_look(
                self.last_snap, self.last_items, self.last_shot_path,
                self.last_shot_size, False, extra,
            )
        extra = f"found: {item.get('name')} [{item.get('ref')}] ({item.get('x')},{item.get('y')})"
        if tap:
            result = self.client.execute([
                {"kind": "tap", "x": item["x"], "y": item["y"]},
                {"kind": "sleep", "ms": 800},
            ])
            return self._commit(result, False, extra)
        return format_look(
            self.last_snap, self.last_items, self.last_shot_path,
            self.last_shot_size, False, extra,
        )

    def _find_scroll(self, query: str, tap: bool) -> str:
        if not self.last_items:
            self._look(False)
        if is_home_screen(self.last_items):
            raise GuardError(
                "vertical scroll on the home screen opens the app drawer; use scan=home_pages or open()"
            )
        w, h = screen_size(self.last_snap)
        item = find_label(strip_chrome(self.last_items, h), query)
        if item:
            extra = f"found: {item.get('name')} [{item.get('ref')}] ({item.get('x')},{item.get('y')})"
            if tap:
                result = self.client.execute([
                    {"kind": "tap", "x": item["x"], "y": item["y"]},
                    {"kind": "sleep", "ms": 800},
                ])
                return self._commit(result, False, extra)
            return format_look(
                self.last_snap, self.last_items, self.last_shot_path,
                self.last_shot_size, False, extra,
            )
        x1, y1, x2, y2 = content_swipe_coords("down", w, h)
        for _ in range(SCROLL_SWIPES_DEFAULT):
            result = self.client.execute([
                {"kind": "swipe", "x1": x1, "y1": y1, "x2": x2, "y2": y2},
                {"kind": "sleep", "ms": PAGE_SWIPE_SLEEP_MS},
            ])
            self._commit(result, False)
            if is_home_screen(self.last_items):
                raise GuardError("ended up on home while scrolling; use open()")
            item = find_label(strip_chrome(self.last_items, h), query)
            if item:
                extra = f"found: {item.get('name')} [{item.get('ref')}] ({item.get('x')},{item.get('y')})"
                if tap:
                    result = self.client.execute([
                        {"kind": "tap", "x": item["x"], "y": item["y"]},
                        {"kind": "sleep", "ms": 800},
                    ])
                    return self._commit(result, False, extra)
                return format_look(
                    self.last_snap, self.last_items, self.last_shot_path,
                    self.last_shot_size, False, extra,
                )
        extra = f"ERROR: '{query}' not found after {SCROLL_SWIPES_DEFAULT} content swipes"
        return format_look(
            self.last_snap, self.last_items, self.last_shot_path,
            self.last_shot_size, False, extra,
        )

    def _find_on_home_pages(self, name: str, max_swipes: int) -> tuple[dict | None, int | None]:
        items = self._go_home()
        w, h = screen_size(self.last_snap)
        icon = find_app_icon(items, name, screen_h=h)
        if icon:
            return icon, 0
        for i in range(max_swipes):
            items = self._page_swipe("left")
            icon = find_app_icon(items, name, screen_h=h)
            if icon:
                return icon, i + 1
        for _ in range(max_swipes):
            self._page_swipe("right")
        for i in range(max_swipes):
            items = self._page_swipe("right")
            icon = find_app_icon(items, name, screen_h=h)
            if icon:
                return icon, -(i + 1)
        for _ in range(max_swipes):
            self._page_swipe("left")
        return None, None

    def _page_swipe(self, direction: str) -> list[dict]:
        w, h = screen_size(self.last_snap)
        x1, y1, x2, y2 = page_swipe_coords(direction, w, h)
        result = self.client.execute([
            {"kind": "swipe", "x1": x1, "y1": y1, "x2": x2, "y2": y2},
            {"kind": "sleep", "ms": PAGE_SWIPE_SLEEP_MS},
        ])
        self._commit(result, False, save=False)
        return self.last_items

    def _reset(self) -> str:
        result = self.client.execute([
            {"kind": "nav", "nav": "home"}, {"kind": "sleep", "ms": 800},
            {"kind": "nav", "nav": "home"}, {"kind": "sleep", "ms": 1000},
            {"kind": "nav", "nav": "back"}, {"kind": "sleep", "ms": NAV_SLEEP_MS},
            {"kind": "nav", "nav": "back"}, {"kind": "sleep", "ms": NAV_SLEEP_MS},
            {"kind": "nav", "nav": "back"}, {"kind": "sleep", "ms": NAV_SLEEP_MS},
            {"kind": "nav", "nav": "home"}, {"kind": "sleep", "ms": 1200},
        ])
        return self._commit(result, False, "reset: back x3 + home")

    def _act(self, **kwargs: Any) -> str:
        kind = (kwargs.get("kind") or "").strip().lower()
        if kind == "tap":
            return self._act_tap(kwargs)
        if kind == "swipe":
            return self._act_swipe(kwargs)
        if kind == "drag":
            return self._act_drag(kwargs)
        if kind in ("long_press", "longpress", "hold"):
            return self._act_long_press(kwargs)
        if kind in ("type", "input"):
            return self._act_type(kwargs)
        if kind == "nav":
            return self._act_nav(kwargs)
        raise GuardError(
            f"unknown act kind {kind!r}; use tap, swipe, drag, long_press, type, nav"
        )

    def _act_tap(self, kw: dict) -> str:
        item = self._resolve_target(kw)
        result = self.client.execute([
            {"kind": "tap", "x": item["x"], "y": item["y"]},
            {"kind": "sleep", "ms": 800},
        ])
        extra = f"tapped: {item.get('name', '')} [{item.get('ref', '')}] ({item['x']},{item['y']})"
        return self._commit(result, False, extra)

    def _act_long_press(self, kw: dict) -> str:
        # kind:longPress hangs the queue. Hold = drag + sleep + release.
        item = self._resolve_target(kw)
        result = self.client.execute([
            {"kind": "drag", "x": item["x"], "y": item["y"]},
            {"kind": "sleep", "ms": 1500},
            {"kind": "release"},
            {"kind": "sleep", "ms": 400},
        ])
        extra = f"long_press: [{item.get('ref', '')}] ({item['x']},{item['y']}) via drag+release"
        return self._commit(result, False, extra)

    def _act_swipe(self, kw: dict) -> str:
        if not self.last_items:
            self._look(False)
        w, h = screen_size(self.last_snap)
        direction = (kw.get("direction") or "").strip().lower()
        on_home = is_home_screen(self.last_items)
        if direction in ("left", "right"):
            x1, y1, x2, y2 = page_swipe_coords(direction, w, h)
        elif direction in ("up", "down"):
            x1, y1, x2, y2 = content_swipe_coords(direction, w, h)
        else:
            x1, y1, x2, y2 = self._coords_pair(kw, w, h)
        reject_bad_swipe(x1, y1, x2, y2, on_home)
        result = self.client.execute([
            {"kind": "swipe", "x1": x1, "y1": y1, "x2": x2, "y2": y2},
            {"kind": "sleep", "ms": PAGE_SWIPE_SLEEP_MS},
        ])
        extra = f"swiped: ({x1},{y1}) -> ({x2},{y2})"
        return self._commit(result, False, extra)

    def _act_drag(self, kw: dict) -> str:
        w, h = screen_size(self.last_snap)
        src = None
        dst = None
        if kw.get("from_ref"):
            src = find_by_ref(self.last_items, kw["from_ref"])
            if not src:
                raise GuardError(f"from_ref {kw['from_ref']!r} is stale; call look() first")
        if kw.get("to_ref"):
            dst = find_by_ref(self.last_items, kw["to_ref"])
            if not dst:
                raise GuardError(f"to_ref {kw['to_ref']!r} is stale; call look() first")
        if src is None:
            x1 = kw.get("x1") if kw.get("x1") is not None else kw.get("x")
            y1 = kw.get("y1") if kw.get("y1") is not None else kw.get("y")
            if x1 is None or y1 is None:
                raise GuardError("drag needs from_ref or x1,y1")
            src = {"x": int(x1), "y": int(y1)}
        if dst is None:
            x2, y2 = kw.get("x2"), kw.get("y2")
            if x2 is None or y2 is None:
                raise GuardError("drag needs to_ref or x2,y2")
            dst = {"x": int(x2), "y": int(y2)}
        x1, y1, x2, y2 = int(src["x"]), int(src["y"]), int(dst["x"]), int(dst["y"])
        reject_bad_swipe(x1, y1, x2, y2, on_home=False)
        # drag (finger down) then swipe-as-move then release — not kind:longPress
        result = self.client.execute([
            {"kind": "drag", "x": x1, "y": y1},
            {"kind": "sleep", "ms": 400},
            {"kind": "swipe", "x1": x1, "y1": y1, "x2": x2, "y2": y2},
            {"kind": "sleep", "ms": 200},
            {"kind": "release"},
            {"kind": "sleep", "ms": 400},
        ])
        extra = f"dragged: ({x1},{y1}) -> ({x2},{y2})"
        return self._commit(result, False, extra)

    def _act_type(self, kw: dict) -> str:
        text = kw.get("text")
        if text is None or text == "":
            raise GuardError("type needs text= (the string to type)")
        field = self._resolve_type_field(kw)
        actions: list[dict] = [
            {"kind": "tap", "x": field["x"], "y": field["y"]},
            {"kind": "sleep", "ms": 800},
            {"kind": "input", "text": str(text), "inputMode": "keys"},
        ]
        if kw.get("submit"):
            actions.append({"kind": "key", "key": "enter"})
        actions.append({"kind": "sleep", "ms": 500})
        result = self.client.execute(actions)
        extra = (
            f"tapped field [{field.get('ref', '')}] ({field['x']},{field['y']}) "
            "then typed (keys mode)"
        )
        return self._commit(result, False, extra)

    def _resolve_type_field(self, kw: dict) -> dict:
        """Focus target for type. `text` is the typed string, never the field label."""
        ref = (kw.get("ref") or "").strip()
        x, y = kw.get("x"), kw.get("y")
        if ref:
            if not self.last_items:
                raise GuardError("no snapshot yet; call look() then type with ref= of the field")
            item = find_by_ref(self.last_items, ref)
            if not item:
                raise GuardError(f"ref {ref!r} is stale; call look() first")
            return item
        if x is not None and y is not None:
            return {"x": int(x), "y": int(y), "name": "", "ref": ""}
        raise GuardError(
            "type needs ref= of the input field from the last look "
            "(it taps that field, then sends keys). "
            "Do not type without a field — keys go to whatever is focused, usually the wrong place."
        )

    def _act_nav(self, kw: dict) -> str:
        nav = (kw.get("nav") or "").strip().lower()
        mapping = {
            "home": "home",
            "back": "back",
            "overview": "recentApps",
            "recent": "recentApps",
            "recentapps": "recentApps",
        }
        if nav not in mapping:
            raise GuardError("nav must be home, back, or overview")
        result = self.client.execute([
            {"kind": "nav", "nav": mapping[nav]},
            {"kind": "sleep", "ms": NAV_SLEEP_MS},
        ])
        extra = f"nav: {nav}"
        return self._commit(result, False, extra)

    def _persist_shot(self) -> None:
        try:
            jpeg = self.client.screenshot_bytes()
        except PhoneError:
            return
        path = save_shot(jpeg) if jpeg else ""
        if path:
            self.last_shot_path = path
            self.last_shot_size = len(jpeg)

    def _resolve_target(self, kw: dict) -> dict:
        ref = (kw.get("ref") or "").strip()
        text = (kw.get("text") or "").strip()
        x, y = kw.get("x"), kw.get("y")
        if ref:
            if not self.last_items:
                raise GuardError("no snapshot yet; call look() before tapping a ref")
            item = find_by_ref(self.last_items, ref)
            if not item:
                raise GuardError(f"ref {ref!r} is stale; call look() first")
            return item
        if text:
            if not self.last_items:
                self._look(False)
            _, h = screen_size(self.last_snap)
            item = find_app_icon(self.last_items, text, screen_h=h) or find_label(
                strip_chrome(self.last_items, h), text
            )
            if not item:
                raise GuardError(f"text {text!r} not on current screen (chrome stripped)")
            return item
        if x is not None and y is not None:
            return {"x": int(x), "y": int(y), "name": "", "ref": ""}
        raise GuardError("tap/long_press needs ref, text, or x,y")

    def _coords_pair(self, kw: dict, screen_w: int, screen_h: int) -> tuple[int, int, int, int]:
        vals = [kw.get(k) for k in ("x1", "y1", "x2", "y2")]
        if any(v is None for v in vals):
            raise GuardError("swipe needs direction=left|right|up|down or x1,y1,x2,y2")
        return int(vals[0]), int(vals[1]), int(vals[2]), int(vals[3])

    def _commit(self, result: dict, include_chrome: bool, extra: str = "", save: bool = True) -> str:
        snap = (result or {}).get("snapshot") or {}
        items = snap.get("items") or []
        if snap:
            self.last_snap = snap
            self.last_items = items
        if save:
            jpeg = b""
            try:
                jpeg = self.client.screenshot_bytes()
            except PhoneError:
                pass
            path = save_shot(jpeg) if jpeg else ""
            if path:
                self.last_shot_path = path
                self.last_shot_size = len(jpeg)
        if not snap:
            extra = (extra + "\n" if extra else "") + "WARN: execute had no snapshot field"
            return format_look(
                self.last_snap, self.last_items, self.last_shot_path,
                self.last_shot_size, include_chrome, extra,
            )
        err = (result or {}).get("lastError") or ""
        if err:
            extra = (extra + "\n" if extra else "") + f"lastError: {err}"
        return format_look(snap, items, self.last_shot_path, self.last_shot_size, include_chrome, extra)
