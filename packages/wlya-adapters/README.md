# WLYA transport adapters (plug-in layout)

Each adapter is a self-contained folder under repo-root `packages/wlya-adapters/<type>/`.
Adding a new adapter should **not** require edits to the Gateway app, Vue UI, or `Registry.kt` — only files in this tree plus a Gradle rebuild (codegen).

## Folder layout

```
packages/wlya-adapters/<type>/
  adapter.json              metadata (type, label, defaults, factoryKind)
  <Type>Adapter.kt          runtime implementation (JVM + Android)
  ui-vue/
    Form.vue                desktop workbench (auto-discovered by Vite glob)
  ui-android/
    <Type>AdapterForm.kt    Gateway Setup / Settings (apps/gateway/app)
```

Example:

```
packages/wlya-adapters/email/
  adapter.json
  EmailAdapter.kt
  EmailAdapterHelpers.kt
  ui-vue/Form.vue
  ui-android/EmailAdapterForm.kt
```

## Checklist: add adapter `mytype`

1. Create `packages/wlya-adapters/mytype/`
2. Add `adapter.json`:
   ```json
   {
     "type": "mytype",
     "label": "My Adapter",
     "adapterClass": "MytypeAdapter",
     "factoryKind": "simple",
     "defaultConfig": { "key": "value" }
   }
   ```
   - `factoryKind: "simple"` — factory `(id, config) -> AdapterClass(id, config)`
   - `factoryKind: "mock"` — `MockAdapter` only (LocalStore + storePath)
3. Implement `MytypeAdapter.kt` extending `BaseAdapter`
4. Add `ui-vue/Form.vue` — props `initialConfig`, emit `config`
5. Add `ui-android/MytypeAdapterForm.kt` implementing `AdapterAndroidForm` (package `com.wlya.core.adapters.mytype.ui.android`)
6. Rebuild (codegen runs automatically):
   ```bash
   ./gradlew :wlya-core:compileKotlin :android-client:app:compileDebugKotlin
   pnpm build
   ```

## Codegen

Task `generateAdapterRegistries` (`gradle/generate-adapters.gradle.kts` at repo root) scans `adapter.json` and `*AdapterForm.kt`, generates:

| Output | Location |
|--------|----------|
| `registerAllAdapters()` | `packages/wlya-core/build/generated/kotlin/.../GeneratedAdapterRegistry.kt` |
| `GeneratedAndroidFormRegistry` | `apps/gateway/app/build/generated/kotlin/.../` |

`wlya-core` compiles adapter Kotlin from `packages/wlya-adapters/` but excludes `**/ui-android/**` and `**/ui-vue/**`; Android forms compile in `apps/gateway/app`.

## Consumers

| Platform | Discovery | Defaults |
|----------|-----------|----------|
| Desktop API | `registerAllAdapters()` → `Registry.list()` | `adapter.json` via codegen |
| Vue workbench | `import.meta.glob('@wlya/adapters/*/ui-vue/Form.vue')` | API `GET /api/adapter-types` |
| Android SetupActivity | `GeneratedAndroidFormRegistry` | form + saved SharedPreferences |

## Email adapter options (reference)

| Config key | Default | Description |
|------------|---------|-------------|
| `sendMode` | `smtp` | `smtp` or `imap` (IMAP APPEND, no SMTP rate limit) |
| `imapFolder` | `INBOX` | poll, send, cleanup folder |
| `smtpTo` | login | SMTP recipient |
| `tunnelMessageTtlSeconds` | `900` | delete stale `[TUNNEL]` messages from mailbox |
