# Media3 FFmpeg Extension Artifact

This directory holds the bundled Media3 FFmpeg decoder artifact used by StreamVault builds.

Bundled artifact:

- `media3-decoder-ffmpeg-1.9.2.aar`

Build provenance for the checked-in artifact:

- Upstream source: `androidx/media` `release` branch
- Upstream commit used here: `5fb3064497`
- Media3 version match: `1.9.2`
- FFmpeg recommendation from upstream README: `release/6.0`
- Supported ABIs: `arm64-v8a`, `armeabi-v7a`
- Enabled decoders: `ac3`, `eac3`, `dca`, `mp2`, `mp3`, `truehd`
- License target: LGPL-compatible build only; do not enable GPL/nonfree codecs

Refresh procedure:

1. Rebuild the AndroidX FFmpeg decoder against the exact Media3 version used by this repo.
2. Verify the packaged AAR contains the Java decoder classes plus native `ffmpegJNI` libraries for the supported ABIs.
3. Replace the local artifact in this directory, update the adjacent `.properties` manifest if needed, and rerun `:player:verifyLocalFfmpegArtifact` plus the player unit tests and an app build.
