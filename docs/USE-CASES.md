# Use cases and roadmap

Canonical “why” for Bekon Suite. Short version lives at the top of the [root README](../README.md).

Docs in this repository are **English** by default.

---

## 1. Emigrant gateway — Line

**Job:** you left the country. A rooted Android with a **local SIM** stays at a trusted address. You place and receive GSM calls through that radio so institutions still hear a domestic number — commissariat, interior ministry, FSIN, banks, whoever.

**Now**

- Self-hosted `wlya-server` (`/v1/call` + message inbox).
- Bekon Phone and the workbench Voice tab join a **room** (HMAC). Protocol field `seed` stays `seed`; UI says Secret / Room.
- Gateway on the home phone: tunnel so the device stays on the wire, plus Line audio modes in [`LINE.md`](LINE.md) (walkie, acoustic GSM, optional Magisk electrical bridge).

**Next**

- First-class dial / answer / hold from the remote client (not only an audio pipe once the call is already up).
- Redis-backed voice rooms (multi-instance relay).
- Magisk `wlya-voice` as the default path for mode C on documented chipsets, without lab serials in the tree.

---

## 2. Give your agent a phone — Control

**Job:** an agent with MCP **has a body**. It taps, swipes, opens apps, pays with your card, doomscrolls Instagram, and lives in the UI like a bored human.

**Now**

- Gateway APK: screen, a11y snapshot, gestures, files, IME.
- `phone-manager` queue API ([`CONTROL-PROTOCOL.md`](CONTROL-PROTOCOL.md)).
- `phone-mcp` tools (`look`, `act`, `open`, …) over that queue. No ADB for the happy path.

**Next**

- Publish MCP as a standalone package (`@bekon/mcp` / pip) so agents do not need the whole monorepo.
- A store build **without** accessibility / hidden IME (full Control stays the GitHub APK). Different `applicationId`.
- Fewer “agent said the API is missing” footguns — keep the queue contract stable.

---

## 3. Old Android as a speaker — room voice

**Job:** a cheap phone on the table replaces Alice and Alexa. You talk to **Hermes** (or any agent) in the room.

**Now**

- Walkie-talkie (mode A): mic/speaker over `/v1/call`.
- Same Gateway can run Control in parallel so the agent still has a screen if you want both.

**Next**

- Always-on speaker mode: wake, barge-in, no “call is in progress” theatre when you are only talking to the agent.
- Wire Hermes (or another harness) as a first-class voice peer in the room, not only MCP-on-the-screen.
- Honest capability probe in UI when a device cannot capture call audio without root.

---

## 4. White List Your Ass — adapters

**Job:** the official path is dead (parking garage, DPI, a country that unplugged you). You still **control the home phone** over whatever still moves: **email**, **Excel / Google Sheets**, messenger **MAX**, or a custom adapter you wrote. Add as many backups as you like so you do not lose the line home.

**WLYA** = **White List Your Ass** — keep a channel on the list when the rest of the net kicked you off.

**Now**

- Native `wlyaserver` (HTTP relay) as primary; **email** as a real backup transport.
- Adapter **duty**: backups sleep (~hourly poll), wake on foreign inbound or when primary fails ([`ARCHITECTURE.md`](ARCHITECTURE.md#adapter-duty)).
- Compile-time adapters in `packages/wlya-adapters/` (codegen into desktop + Gateway). Mock adapter for local tests.

**Next**

- Lua host in desktop **and** Android so a script is data, not a new APK: Telegram, Sheets, stego-email, **MAX**, anything with `http` / `mail`.
- Generic form + advertise script+config to the phone over a live channel.
- Reference scripts: `telegram.lua`, `sheets.lua`, `email-stego.lua`, `max.lua`.

Not planned soon: DEX/JAR as the main plugin path, unsigned native markets inside one APK, replacing native `wlyaserver` / `email` with Lua.

---

## Shared later

Be Konnected site; split `wlya-server` into its own deploy repo if versions diverge.

---

## What not to confuse

| | Tunnel | Voice room |
|--|--------|------------|
| Use | Commands, files, Control | PCM / walkie / GSM |
| Identity | Channel id (`seed`) | Room name |
| Clients | Gateway, desktop, adapters | Bekon Phone, Voice tab, Gateway Line service |
