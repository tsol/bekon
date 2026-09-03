#!/usr/bin/env python3
"""Phone MCP for Hermes. HTTP facade over phone-control-api :18082."""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from fastmcp import FastMCP

from nav import Phone

HOST = os.getenv("PHONE_MCP_HOST", "0.0.0.0")
PORT = int(os.getenv("PHONE_MCP_PORT", "18083"))

mcp = FastMCP("phone")
phone = Phone()


@mcp.tool()
def look(include_chrome: bool = False) -> str:
    """Fresh screen snapshot as compact a11y labels plus a screenshot file path.

    Status bar (battery/time) and Android nav bar are stripped by default.
    Set include_chrome=true only if you need those. Feed the screenshot path
    to Hermes vision if you need to see pixels. Do not hardcode coordinates —
    tap by ref from this look. To type into a field, call
    act(kind=type, ref=that_field, text=...). Type always taps the field first.
    """
    return phone.look(include_chrome=include_chrome)


@mcp.tool()
def open(name: str, max_swipes: int = 4) -> str:
    """Open an app from the home screen by visible name.

    Goes home, swipes launcher pages both ways until the label is found, taps it,
    waits for load, returns a fresh look. Use this instead of swiping yourself.
    """
    return phone.open(name, max_swipes=max_swipes)


@mcp.tool()
def find(query: str, tap: bool = False, scan: str = "screen") -> str:
    """Find a label. scan=screen (current), home_pages (swipe launcher), or scroll
    (vertical content — refused on the home screen, that opens the app drawer).
    tap=true taps the first match. Skips status-bar notifications.
    """
    return phone.find(query, tap=tap, scan=scan)


@mcp.tool()
def act(
    kind: str,
    ref: str = "",
    text: str = "",
    x: int | None = None,
    y: int | None = None,
    x1: int | None = None,
    y1: int | None = None,
    x2: int | None = None,
    y2: int | None = None,
    direction: str = "",
    nav: str = "",
    submit: bool = False,
    from_ref: str = "",
    to_ref: str = "",
) -> str:
    """Gesture, then a fresh look.

    kind: tap | swipe | drag | long_press | type | nav
    tap/long_press: prefer ref from the last look, else text, else x,y.
    swipe: direction=left|right|up|down, or x1,y1,x2,y2. Vertical swipe on home is refused.
    drag: from_ref/to_ref or x1,y1,x2,y2 (finger-down, move, lift — not longPress).
    type: MUST pass ref= of the field from the last look (or x,y). It taps that
    field first, then types text= as keystrokes. submit=true sends Enter.
    Type without ref is refused — do not assume a field is focused.
    nav: home | back | overview.
    To open an app use open(), not a raw swipe.
    """
    return phone.act(
        kind=kind,
        ref=ref or None,
        text=text or None,
        x=x,
        y=y,
        x1=x1,
        y1=y1,
        x2=x2,
        y2=y2,
        direction=direction or None,
        nav=nav or None,
        submit=submit,
        from_ref=from_ref or None,
        to_ref=to_ref or None,
    )


@mcp.tool()
def reset() -> str:
    """Leave overlays/apps and return to the home screen (back x3, home x2)."""
    return phone.reset()


@mcp.tool()
def see(question: str) -> str:
    """Does not call a vision model. Returns the latest screenshot path and compact
    labels so you can answer `question` with Hermes vision on that file.
    """
    return phone.see(question)


if __name__ == "__main__":
    mcp.run(transport="streamable-http", host=HOST, port=PORT)
