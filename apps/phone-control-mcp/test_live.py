#!/usr/bin/env python3
"""Live smoke against phone-control-api. Skipped if :18082 is down or no running tunnel."""
from __future__ import annotations

import os
import sys
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from client import PhoneClient, PhoneError
from nav import Phone

BASE = os.getenv("PHONE_API", "http://127.0.0.1:18082")


def manager_up() -> bool:
    try:
        with urllib.request.urlopen(f"{BASE}/health", timeout=2) as resp:
            return resp.status == 200
    except (urllib.error.URLError, TimeoutError, OSError):
        return False


def has_running_tunnel() -> bool:
    try:
        tunnels = PhoneClient(BASE).list_tunnels()
    except PhoneError:
        return False
    return any(t.get("running") for t in tunnels)


@unittest.skipUnless(manager_up(), "phone-control-api not on :18082")
@unittest.skipUnless(has_running_tunnel() or os.getenv("PHONE_TUNNEL"), "no running tunnel")
class LiveSmoke(unittest.TestCase):
    def test_look_then_optional_open(self):
        phone = Phone()
        text = phone.look()
        self.assertIn("screenshot:", text)
        self.assertIn("screen:", text)
        path = ""
        for line in text.splitlines():
            if line.startswith("screenshot:"):
                path = line.split(":", 1)[1].strip()
        self.assertTrue(path.endswith(".jpg"), text)
        host_path = path
        if path.startswith("/opt/data/workspace/"):
            from look import workspace_host
            root = workspace_host()
            self.assertIsNotNone(root)
            host_path = str(root / path[len("/opt/data/workspace/"):])
        self.assertTrue(os.path.isfile(host_path), host_path)
        # chrome-off: nav-bar "Главный экран" should not be listed
        item_block = text.split("items:", 1)[-1]
        self.assertNotIn("Главный экран", item_block)
        # open is slow; only if TELEGRAM_SMOKE=1
        if os.getenv("TELEGRAM_SMOKE") == "1":
            opened = phone.open("Telegram")
            self.assertIn("screenshot:", opened)
            found = phone.find("memory")
            self.assertIn("screenshot:", found)


if __name__ == "__main__":
    unittest.main()
