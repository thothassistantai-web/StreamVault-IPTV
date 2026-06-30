# Tiered Special Events (Gateway)

Special Events tiers **1–5** shipped in StepDaddy Gateway **v3.0.11**. StreamVault **3.0.1** consumes gateway M3U titles (including health dots) and strips display prefixes for browse UI.

Full tier documentation: [stepdaddy-gateway-android/docs/TIER-RELEASES.md](https://github.com/thothassistantai-web/stepdaddy-gateway-android/blob/main/docs/TIER-RELEASES.md).

## StreamVault verification

```bash
curl -s 'http://127.0.0.1:3000/health?lite=1' | jq '{ok,channels,version}'
curl -s 'http://127.0.0.1:3000/streamvault.m3u' | head -20
```

Import `http://127.0.0.1:3000/streamvault.m3u` in StreamVault and confirm Special Events categories appear with programme data in the guide.
