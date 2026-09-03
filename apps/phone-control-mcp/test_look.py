#!/usr/bin/env python3
"""Unit tests for compact look, chrome strip, swipe guards — no phone required."""
from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from client import ensure_trailing_snapshot
from look import (
    GuardError,
    classify_region,
    format_look,
    hermes_visible_path,
    is_home_screen,
    reject_bad_swipe,
    save_shot,
    shot_hint,
    strip_chrome,
    swipe_is_vertical,
)


def item(ref, name, x, y, source="a11y"):
    return {"ref": ref, "name": name, "x": x, "y": y, "source": source}


HOME_ITEMS = [
    item("e0", "12:41", 40, 10),
    item("e1", "Поиск в Google", 240, 100),
    item("e2", "Instagram", 184, 261),
    item("e3", "Главный экран", 240, 928),
]


class LookTests(unittest.TestCase):
    def test_regions(self):
        self.assertEqual(classify_region(10), "status_bar")
        self.assertEqual(classify_region(100), "search_bar")
        self.assertEqual(classify_region(261), "app_grid")
        self.assertEqual(classify_region(850), "dock")
        self.assertEqual(classify_region(928), "nav_bar")

    def test_strip_chrome_drops_status_and_nav(self):
        visible = strip_chrome(HOME_ITEMS)
        names = [i["name"] for i in visible]
        self.assertIn("Instagram", names)
        self.assertIn("Поиск в Google", names)
        self.assertNotIn("12:41", names)
        self.assertNotIn("Главный экран", names)

    def test_format_look_hides_chrome_by_default(self):
        snap = {"screenW": 480, "screenH": 960, "items": HOME_ITEMS}
        text = format_look(snap, HOME_ITEMS, "/tmp/phone-control-mcp/x.jpg", 300_000, include_chrome=False)
        self.assertIn("Instagram", text)
        self.assertNotIn("12:41", text)
        self.assertNotIn("Главный экран", text)
        self.assertIn("home: true", text)
        self.assertIn("screenshot: /tmp/phone-control-mcp/x.jpg", text)

    def test_format_look_include_chrome(self):
        snap = {"screenW": 480, "screenH": 960}
        text = format_look(snap, HOME_ITEMS, "/tmp/x.jpg", 300_000, include_chrome=True)
        self.assertIn("12:41", text)
        self.assertIn("Главный экран", text)

    def test_glavny_ekran_is_not_home(self):
        in_app = [
            item("e1", "Reels", 144, 867),
            item("e2", "Главный экран", 240, 928),
        ]
        self.assertFalse(is_home_screen(in_app))
        self.assertTrue(is_home_screen(HOME_ITEMS))

    def test_shot_hint(self):
        self.assertEqual(shot_hint(40_000)[0], "stale")
        self.assertEqual(shot_hint(160_000)[0], "overlay")
        self.assertEqual(shot_hint(300_000)[0], "fresh")

    def test_save_shot_rotates(self):
        d = Path(tempfile.mkdtemp())
        for _ in range(3):
            save_shot(b"jpeg-bytes", shot_dir=str(d), keep=2)
        self.assertEqual(len(list(d.glob("*.jpg"))), 2)

    def test_hermes_visible_path_rewrites_workspace(self):
        host = tempfile.mkdtemp()
        os.environ["PHONE_WORKSPACE_HOST"] = host
        os.environ["PHONE_WORKSPACE_HERMES"] = "/opt/data/workspace"
        try:
            sample = os.path.join(host, "apps", "phone-control-mcp", "shots", "a.jpg")
            Path(sample).parent.mkdir(parents=True, exist_ok=True)
            out = hermes_visible_path(sample)
            self.assertEqual(out, "/opt/data/workspace/apps/phone-control-mcp/shots/a.jpg")
        finally:
            os.environ.pop("PHONE_WORKSPACE_HOST", None)
            os.environ.pop("PHONE_WORKSPACE_HERMES", None)

    def test_hermes_visible_path_leaves_tmp(self):
        self.assertEqual(hermes_visible_path("/tmp/phone-control-mcp/x.jpg"), "/tmp/phone-control-mcp/x.jpg")


class GuardTests(unittest.TestCase):
    def test_vertical_on_home_rejected(self):
        with self.assertRaises(GuardError) as ctx:
            reject_bad_swipe(240, 700, 240, 200, on_home=True)
        self.assertIn("app drawer", str(ctx.exception))

    def test_horizontal_on_home_ok(self):
        reject_bad_swipe(400, 500, 40, 500, on_home=True)

    def test_vertical_off_home_ok(self):
        reject_bad_swipe(240, 600, 240, 200, on_home=False)

    def test_zero_distance_rejected(self):
        with self.assertRaises(GuardError) as ctx:
            reject_bad_swipe(184, 261, 184, 261, on_home=False)
        self.assertIn("long_press", str(ctx.exception))

    def test_swipe_is_vertical(self):
        self.assertTrue(swipe_is_vertical(240, 600, 240, 200))
        self.assertFalse(swipe_is_vertical(400, 500, 40, 500))


class SnapshotBatchTests(unittest.TestCase):
    def test_appends_snapshot(self):
        out = ensure_trailing_snapshot([{"kind": "tap", "x": 1, "y": 2}])
        self.assertEqual(out[-1]["kind"], "snapshot")

    def test_keeps_existing_trailing_snapshot(self):
        actions = [{"kind": "tap", "x": 1, "y": 2}, {"kind": "snapshot"}]
        self.assertEqual(ensure_trailing_snapshot(actions), actions)

    def test_trailing_sleep_still_counts_as_having_snapshot(self):
        actions = [{"kind": "snapshot"}, {"kind": "sleep", "ms": 100}]
        self.assertEqual(ensure_trailing_snapshot(actions), actions)


if __name__ == "__main__":
    unittest.main()
