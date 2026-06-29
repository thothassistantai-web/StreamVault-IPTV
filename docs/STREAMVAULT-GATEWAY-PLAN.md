# StreamVault × StepDaddy Gateway Integration Plan

**Fork:** [thothassistantai-web/StreamVault-IPTV](https://github.com/thothassistantai-web/StreamVault-IPTV)  
**Gateway:** [stepdaddy-gateway-android](https://github.com/thothassistantai-web/stepdaddy-gateway-android) **v3.0.0**  
**StreamVault:** **v3.0.0**  
**Updated:** 2026-06-28

See the gateway repo copy for the full phase map: [STREAMVAULT-GATEWAY-PLAN.md](https://github.com/thothassistantai-web/stepdaddy-gateway-android/blob/main/docs/STREAMVAULT-GATEWAY-PLAN.md).

## Stable v3.0.0 checklist

- [x] `streamvault.m3u` + `epg.xml` import via plugin and manual URL
- [x] `GatewayConstants` path prefixes match gateway `PlaylistPaths`
- [x] `GatewayLifecycleManager` + `GatewayRecoveryWorker`
- [x] Special event health dots stripped for display (`M3uChannelDisplayName`)
- [x] Resume last live channel toggle
- [x] Browse/guide/search caches for large gateway catalogs
- [x] Signed release APKs on both repos

## Quick setup

1. Install Gateway **3.0.0** → start server → verify `/health?lite=1`
2. Install StreamVault **3.0.0**
3. Plugins → StepDaddy Gateway → Test connection → Add provider

## Related

- [GATEWAY.md](GATEWAY.md) — StreamVault-side integration
- [PLUGIN_API.md](PLUGIN_API.md) — host plugin contract
- [RELEASE.md](RELEASE.md) — version bump and GitHub release
