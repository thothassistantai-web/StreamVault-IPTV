# StreamVault Plugin API

StreamVault plugins are companion Android APKs. They do not inject code into the
main application. Instead, StreamVault discovers installed APKs that expose a
bound service with the action `com.streamvault.plugin.API` and talks to them
through Android `Messenger` IPC.

This keeps the plugin boundary installable, removable, and compatible with
Android package isolation while still allowing plugins to add provider, playback,
Cast, and host-rendered configuration capabilities.

## Package Discovery

The host queries services for:

```xml
<action android:name="com.streamvault.plugin.API" />
```

The host app must declare the same action in `<queries>`. Plugin APKs should
declare an exported service:

```xml
<service
    android:name=".MyPluginService"
    android:exported="true">
    <intent-filter>
        <action android:name="com.streamvault.plugin.API" />
    </intent-filter>
</service>
```

Plugins can be installed from:

- A direct HTTP/HTTPS APK URL.
- A local APK selected with the system file picker.
- Any manual Android package install. StreamVault detects it after refreshing the
  Plugins screen.

## Manifest

StreamVault first asks the plugin service for its manifest. As a fallback, it
reads `com.streamvault.plugin.MANIFEST_JSON` service metadata, then individual
metadata fields if the JSON is missing or invalid.

```json
{
  "schemaVersion": 1,
  "id": "com.example.plugin",
  "name": "Example Plugin",
  "versionName": "1.0.0",
  "versionCode": 1,
  "description": "Adds external capabilities to StreamVault.",
  "providerName": "Example Provider",
  "configurationMode": "host.schema",
  "configurationActivityAction": "com.example.plugin.CONFIGURE",
  "capabilities": [
    "provider.m3u",
    "playback.prepare",
    "cast.rewriteUrl",
    "configuration.schema",
    "configuration.activity"
  ]
}
```

Recommended fallback metadata:

```xml
<meta-data android:name="com.streamvault.plugin.ID" android:value="com.example.plugin" />
<meta-data android:name="com.streamvault.plugin.NAME" android:value="Example Plugin" />
<meta-data android:name="com.streamvault.plugin.VERSION_NAME" android:value="1.0.0" />
<meta-data android:name="com.streamvault.plugin.VERSION_CODE" android:value="1" />
<meta-data android:name="com.streamvault.plugin.DESCRIPTION" android:value="Adds external capabilities to StreamVault." />
<meta-data android:name="com.streamvault.plugin.PROVIDER_NAME" android:value="Example Provider" />
<meta-data android:name="com.streamvault.plugin.CONFIGURATION_MODE" android:value="host.schema" />
<meta-data android:name="com.streamvault.plugin.CONFIGURATION_ACTIVITY_ACTION" android:value="com.example.plugin.CONFIGURE" />
<meta-data android:name="com.streamvault.plugin.CAPABILITIES" android:value="provider.m3u,playback.prepare,cast.rewriteUrl,configuration.schema,configuration.activity" />
```

Capability names:

- `provider.m3u`: the plugin can expose an M3U URL that StreamVault imports as a
  provider when enabled.
- `playback.prepare`: the plugin can prepare a stream URL before playback starts.
- `cast.rewriteUrl`: the plugin can rewrite a playback URL before Google Cast
  loads it.
- `configuration.schema`: the plugin exposes a declarative configuration schema
  that StreamVault renders with its own UI.
- `configuration.activity`: fallback capability for plugins that need a custom
  Android activity for exceptional flows.

## IPC Messages

Every request includes:

- `api_version`: current value is `1`.
- `request_id`: opaque ID copied back by the plugin response.

Every response should include:

- `api_version`
- `request_id`
- `success`
- Optional `message`

Messages:

| ID | Name | Purpose |
| --- | --- | --- |
| 1 | `MSG_GET_MANIFEST` | Return `manifest_json`. |
| 2 | `MSG_SET_ENABLED` | Start or stop plugin functionality using `enabled`. |
| 3 | `MSG_GET_STATUS` | Return a short `status_label` and optional `message`. |
| 4 | `MSG_GET_PROVIDER_URL` | Return `url` and optional `provider_name`. |
| 5 | `MSG_PREPARE_PLAYBACK` | Prepare `input_url`; set `handled` when relevant. |
| 6 | `MSG_REWRITE_CAST_URL` | Rewrite `input_url`; return `output_url` when relevant. |
| 7 | `MSG_GET_CONFIGURATION_SCHEMA` | Return `configuration_schema_json`. |
| 8 | `MSG_GET_CONFIGURATION_VALUES` | Return `configuration_values_json`. |
| 9 | `MSG_SET_CONFIGURATION_VALUES` | Persist `configuration_values_json`. |
| 10 | `MSG_RUN_CONFIGURATION_ACTION` | Run `configuration_action_id`. |

For `playback.prepare` and `cast.rewriteUrl`, plugins should set `handled=false`
when the URL is not theirs. StreamVault then continues with other enabled
plugins or the original URL.

## Host-Rendered Configuration

Host-rendered configuration is the standard configuration mode. The plugin
describes fields and actions, but StreamVault owns the visual shell, focus
behavior, typography, controls, validation placement, and feedback.

A plugin opts in with:

- Manifest field `configurationMode: "host.schema"`.
- Capability `configuration.schema`.
- IPC handlers for messages 7 through 10.

Schema response:

```json
{
  "schemaVersion": 1,
  "title": "Example Plugin",
  "description": "Settings rendered by StreamVault.",
  "sections": [
    {
      "id": "connection",
      "title": "Connection",
      "description": "Endpoint used by this plugin.",
      "fields": [
        {
          "key": "serverUrl",
          "type": "url",
          "label": "Server URL",
          "placeholder": "http://192.168.1.20:8080",
          "required": true
        },
        {
          "key": "token",
          "type": "password",
          "label": "Token",
          "secret": true
        },
        {
          "key": "lanMode",
          "type": "boolean",
          "label": "LAN mode",
          "description": "Expose local URLs to other devices."
        },
        {
          "key": "quality",
          "type": "select",
          "label": "Preferred quality",
          "options": [
            { "value": "auto", "label": "Auto" },
            { "value": "1080p", "label": "1080p" }
          ]
        },
        {
          "key": "status",
          "type": "info",
          "label": "Status",
          "readOnly": true
        }
      ]
    }
  ],
  "actions": [
    {
      "id": "testConnection",
      "label": "Test connection",
      "description": "Ask the plugin to validate its current settings.",
      "refreshAfterRun": true
    }
  ]
}
```

Supported field types:

- `info`: read-only text rendered by StreamVault.
- `text`: single-line text.
- `password`: single-line secret text.
- `url`: URL text field.
- `number`: numeric text field.
- `boolean`: switch.
- `select`: one value from `options`.
- `textarea`: multi-line text.

Values response:

```json
{
  "serverUrl": "http://192.168.1.20:8080",
  "token": "",
  "lanMode": true,
  "quality": "auto",
  "status": "Ready"
}
```

For `MSG_SET_CONFIGURATION_VALUES`, StreamVault sends the same JSON object in
`configuration_values_json`. Plugins should persist supported writable keys,
ignore unknown or read-only keys, and return `success=false` with `message` when
validation fails.

For `MSG_RUN_CONFIGURATION_ACTION`, StreamVault sends `configuration_action_id`.
Plugins should execute the action, return a user-facing `message`, and refresh
their values when `refreshAfterRun` is true.

## Configuration Activity Fallback

If a plugin cannot express a flow declaratively, it can still expose
`configuration.activity`. StreamVault starts `configurationActivityAction` with
the plugin package set explicitly. The plugin activity should be exported and
include the `DEFAULT` category. Use this only for flows that need custom UI, such
as OAuth, device pairing, or an embedded web surface.
