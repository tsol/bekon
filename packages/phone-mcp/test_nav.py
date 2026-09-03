#!/usr/bin/env python3
"""Phone ops with a fake phone-manager — no device required."""
from __future__ import annotations

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from nav import Phone


def snap(*items):
    return {
        "snapshot": {
            "items": list(items),
            "screenW": 480,
            "screenH": 960,
            "ocrCount": 0,
        }
    }


def it(ref, name, x, y):
    return {"ref": ref, "name": name, "x": x, "y": y, "source": "a11y"}


HOME = snap(
    it("e0", "12:41", 40, 10),
    it("e1", "Поиск в Google", 240, 100),
    it("e2", "Instagram", 184, 261),
    it("e3", "Главный экран", 240, 928),
)
HOME_NO_IG = snap(
    it("e1", "Поиск в Google", 240, 100),
    it("e2", "Chrome", 77, 261),
    it("e3", "Главный экран", 240, 928),
)
PAGE_IG = snap(
    it("e1", "Поиск в Google", 240, 100),
    it("e2", "Instagram", 184, 261),
    it("e3", "Главный экран", 240, 928),
)
IG_OPEN = snap(
    it("e1", "Reels", 144, 867),
    it("e2", "Профиль", 432, 867),
    it("e3", "Главный экран", 240, 928),
)


class FakeClient:
    def __init__(self, replies):
        self.replies = list(replies)
        self.calls = []

    def execute(self, actions):
        self.calls.append(actions)
        if not self.replies:
            return snap()
        return self.replies.pop(0)

    def snapshot(self):
        return self.execute([{"kind": "snapshot"}])

    def screenshot_bytes(self):
        return b"x" * 300_000


class NavTests(unittest.TestCase):
    def setUp(self):
        os.environ["PHONE_SHOTS"] = tempfile.mkdtemp()

    def test_look_strips_chrome(self):
        p = Phone(FakeClient([HOME]))
        text = p.look()
        self.assertIn("Instagram", text)
        self.assertNotIn("12:41", text)
        self.assertNotIn("[e3] Главный экран", text)
        self.assertIn("home: true", text)
        self.assertTrue(text.split("screenshot: ")[1].split("\n")[0].endswith(".jpg"))

    def test_open_taps_icon_on_first_page(self):
        fake = FakeClient([HOME, IG_OPEN])
        p = Phone(fake)
        text = p.open("Instagram")
        self.assertIn("opened:", text)
        self.assertIn("Reels", text)
        self.assertIn("home: false", text)
        tap = [a for batch in fake.calls for a in batch if a.get("kind") == "tap"]
        self.assertEqual(tap[0]["x"], 184)
        self.assertEqual(tap[0]["y"], 261)
        self.assertFalse(any(a.get("kind") == "longPress" for batch in fake.calls for a in batch))

    def test_open_swipes_then_finds(self):
        fake = FakeClient([HOME_NO_IG, PAGE_IG, IG_OPEN])
        p = Phone(fake)
        text = p.open("Instagram")
        self.assertIn("opened:", text)
        swipes = [a for batch in fake.calls for a in batch if a.get("kind") == "swipe"]
        self.assertTrue(swipes)
        self.assertEqual(swipes[0]["y1"], 500)

    def test_vertical_swipe_on_home_errors(self):
        p = Phone(FakeClient([HOME]))
        p.look()
        text = p.act(kind="swipe", direction="down")
        self.assertTrue(text.startswith("ERROR:"))
        self.assertIn("app drawer", text)

    def test_long_press_uses_drag_not_longPress(self):
        fake = FakeClient([HOME, HOME])
        p = Phone(fake)
        p.look()
        p.act(kind="long_press", ref="e2")
        kinds = [a.get("kind") for batch in fake.calls for a in batch]
        self.assertIn("drag", kinds)
        self.assertIn("release", kinds)
        self.assertNotIn("longPress", kinds)

    def test_type_uses_keys(self):
        fake = FakeClient([HOME, HOME])
        p = Phone(fake)
        p.look()
        p.act(kind="type", ref="e1", text="hello")
        batch = fake.calls[-1]
        kinds = [a.get("kind") for a in batch]
        self.assertEqual(kinds[:3], ["tap", "sleep", "input"])
        self.assertEqual(batch[0]["x"], 240)
        self.assertEqual(batch[0]["y"], 100)
        inputs = [a for a in batch if a.get("kind") == "input"]
        self.assertEqual(inputs[0]["inputMode"], "keys")
        self.assertEqual(inputs[0]["text"], "hello")

    def test_type_without_field_refused(self):
        fake = FakeClient([HOME])
        p = Phone(fake)
        p.look()
        text = p.act(kind="type", text="hello")
        self.assertTrue(text.startswith("ERROR:"))
        self.assertIn("ref=", text)

    def test_stale_ref(self):
        p = Phone(FakeClient([HOME]))
        p.look()
        text = p.act(kind="tap", ref="e999")
        self.assertIn("stale", text)

    def test_find_scroll_refused_on_home(self):
        p = Phone(FakeClient([HOME]))
        p.look()
        text = p.find("Instagram", scan="scroll")
        self.assertTrue(text.startswith("ERROR:"))
        self.assertIn("app drawer", text)


if __name__ == "__main__":
    unittest.main()
