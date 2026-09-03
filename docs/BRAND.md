# Names

**WLYA** is the wire (tunnel, adapters, relay). **White List Your Ass** — a channel you keep when the official internet dropped you. **Bekon** is the device in the room.

Slogan: **Be Konnected.** Line: *WLYA in the wire. Bekon in the room.*

| Name | What to call it |
|------|-----------------|
| WLYA Tunnel | Transport. Expansion: White List Your Ass (use case 4). Wire JSON still uses `seed` (do not rename). UI: **Secret**. |
| Bekon Control | Full Gateway: screen, gestures, MCP. Package `pro.potoki.bekon`. |
| Bekon Line | GSM / walkie. Client app **Bekon Phone** (`pro.potoki.bekon.phone`). |
| Bekon Suite | This monorepo. |

Kotlin core stays `com.wlya.core` / `com.wlya.desktop`. Device packages stay `pro.potoki.bekon.*`.

Do not title the store listing “Agentic Phone”, “Hermes Phone”, or “Hermes Agent”. Hermes is an agent that *uses* Bekon (use case 3), not the APK name.

A future Play build is tunnel + radio only (no a11y remote control) under a **different** `applicationId`. The GitHub APK is the full Control build. Do not advertise GitHub as “turn on remote control” inside a Play listing. Keep the WLYA expansion off the Play card if the store rejects it; it belongs in this repo and use case 4.
