# StepDaddy Gateway integration

StreamVault in this fork pairs with **StepDaddy Gateway** (`com.thothassistant.stepdaddy.gateway` release or `.debug` for dev builds) for live TV, special events, and XMLTV EPG on the same device or over LAN.

## Quick setup (same device)

1. Install [StepDaddy Gateway](https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/tag/v3.0.0) **3.0.0** release (`com.thothassistant.stepdaddy.gateway`) and wait until the HUD shows channel count ready.
2. Install StreamVault from this fork's GitHub Release.
3. Open **Plugins** → enable **StepDaddy Gateway** → **Test connection** → **Add provider**.
4. StreamVault imports `streamvault.m3u` and `epg.xml` from the gateway automatically (legacy `streamvault-setup-playlist.m3u8` still works).

## Manual M3U URL (no plugin)

Works on any StreamVault build:

| Scope | Playlist URL | EPG |
|-------|--------------|-----|
| Same device | `http://127.0.0.1:3000/streamvault.m3u` | `http://127.0.0.1:3000/epg.xml` |
| LAN client | `http://<gateway-lan-ip>:3000/streamvault.m3u` | `http://<gateway-lan-ip>:3000/epg.xml` |

Legacy `streamvault-setup-playlist.m3u8` remains available for existing bookmarks.

Provider Setup → M3U → paste the playlist URL. EPG syncs in the background from the playlist header or the explicit EPG URL.

## Debug dev seed

Debug builds can auto-add the gateway on first boot via `local.properties`:

```properties
m3u.dev.url=http://127.0.0.1:3000/streamvault.m3u
m3u.dev.name=StepDaddy Gateway
```

See [DEV_SEEDING.md](DEV_SEEDING.md).

## Plugin API

The embedded Gateway plugin uses `provider.m3u` and returns both `url` and `epg_url` from `MSG_GET_PROVIDER_URL`. See [PLUGIN_API.md](PLUGIN_API.md).

## Health check

```bash
curl -s http://127.0.0.1:3000/health?lite=1 | jq '{ok,channels,supplementChannels}'
```

Expect `ok: true` and `channels > 0` before importing.

## Auto-start and crash recovery

When the **StepDaddy Gateway** plugin is enabled, StreamVault coordinates gateway lifecycle automatically:

| Trigger | Behavior |
|---------|----------|
| Plugin sync / enable | `GatewayLifecycleManager.ensureGatewayReady()` probes `GET /health?lite=1`, wakes `ServerService` via `com.thothassistant.stepdaddy.gateway.action.START`, falls back to Gateway `MainActivity`, polls up to 120s with stable channel probes |
| Gateway-managed playback (`127.0.0.1:3000/tivimate-stream/…`, etc.) | Ensures HTTP is up before `preparePlaybackStreamInfo` (catalog not required) |
| Background watchdog | `GatewayRecoveryWorker` runs every 15 minutes — wakes gateway if offline, re-syncs the plugin M3U provider when HTTP recovers |

Cross-app wake targets `com.thothassistant.stepdaddy.gateway.debug` (G Pad) then release package. In-process nudge uses plugin message `MSG_ENSURE_GATEWAY` (11), which calls gateway `GatewayStartHelper.ensureGatewayReady()`.

Implementation: `app/src/main/java/com/streamvault/app/plugins/GatewayLifecycleManager.kt`, `GatewayRecoveryWorker.kt`, `GatewayConstants.kt`.

## Related docs

- Gateway install: `stepdaddy-android/docs/STOCK-TIVIMATE-SETUP.md` (monorepo)
- Full integration plan: `docs/STREAMVAULT-GATEWAY-PLAN.md` (monorepo root)
