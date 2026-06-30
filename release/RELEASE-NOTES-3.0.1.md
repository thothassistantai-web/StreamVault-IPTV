# StreamVault 3.0.1

**Suite release** — pairs with [StepDaddy Gateway 3.0.11](https://github.com/thothassistantai-web/stepdaddy-gateway-android/releases/tag/v3.0.11).

## Added

- **Gateway audio playback** — reads `audio_json` from StepDaddy Gateway plugin `playback.prepare` and applies amplification gain to live/VOD playback (configure in Gateway → Settings → Audio)
- Loudness normalization follows Media3 codec loudness metadata when streams include it (gateway toggle exposed for suite alignment)

## Install

Sideload `StreamVault-3.0.1.apk` or `StreamVault.apk` from this release.

Pair with Gateway **3.0.11** on the same device: Plugins → StepDaddy Gateway → Test connection → Add provider.
