# 06 — Security Issues

Credential handling, network security, file access, and data protection.

---

## Overall Security Posture: ✅ GOOD with exceptions

StreamVault's security foundation is solid — credentials are encrypted with AES-GCM via Android Keystore, cleartext traffic is disabled, permissions are minimal, and there are no WebViews or exported services. The issues below are the remaining gaps.

---

## 1. ⚠️ FALSE POSITIVE — `file://` URI Exposure

> See [01_CRITICAL_BLOCKERS.md #15](01_CRITICAL_BLOCKERS.md#15-file-uri-exposure)

---

## 2. ✅ FIXED — Credentials Can Leak Into Crash Logs

> See [01_CRITICAL_BLOCKERS.md #9](01_CRITICAL_BLOCKERS.md#9-credentials-can-leak-into-crash-logs)

---

## 3. ✅ FIXED — M3U URL Injection Vectors

**File:** `data/src/main/java/com/streamvault/data/parser/M3uParser.kt`  
**Severity:** 🟠 HIGH

While `UrlSecurityPolicy.isAllowedImportedUrl()` exists, the following attack vectors are not fully mitigated:

| Vector | Risk |
|--------|------|
| CRLF injection (`%0D%0A`) | HTTP request smuggling if URLs are used in raw HTTP |
| `file:///` protocol entries | Local filesystem reads |
| `javascript:` / `data:` URIs | XSS if ever rendered in WebView (currently no WebView) |
| Extremely long URLs (>8KB) | Buffer overflow in HTTP clients |

**Fix:** After parsing, validate all entry URLs with `URI.create()` and whitelist only `http`/`https` schemes.

---

## 4. ✅ FIXED — Provider Credentials in Domain Model `toString()`

**File:** `domain/src/main/java/com/streamvault/domain/model/Provider.kt`  
**Severity:** 🟡 MEDIUM

```kotlin
data class Provider(
    val username: String = "",
    val password: String = "",
    ...
)
```

Kotlin `data class` auto-generates `toString()` that includes all fields. Any log statement, debug output, or crash report that calls `provider.toString()` will expose credentials in plaintext.

**Fix:** Override `toString()`:
```kotlin
override fun toString(): String = "Provider(id=$id, name=$name, type=$type)"
```

---

## 5. ✅ FIXED — M3U Input Not Length-Bounded

> See [01_CRITICAL_BLOCKERS.md #14](01_CRITICAL_BLOCKERS.md#14-m3u-input-not-length-bounded-dos-vector)

---

## 6. ⏭️ N/A — No Certificate Pinning

**Severity:** 🟡 MEDIUM

No SSL/TLS certificate pinning is implemented for API communications. This is typical for IPTV apps (which connect to diverse user-provided servers), but if there are any first-party API endpoints (analytics, updates, license checks), they should use certificate pinning.

**Note:** Certificate pinning for user-provided IPTV servers is impractical since each provider has different certificates. This is acceptable.

---

## 7. ✅ FIXED — Backup File Not Encrypted

**File:** `data/src/main/java/com/streamvault/data/manager/BackupManagerImpl.kt`  
**Severity:** 🟡 MEDIUM

Exported backup files contain:
- Provider configurations (server URLs, potentially with embedded credentials in M3U URLs)
- Favorites and watch history
- Settings

These are stored as plain JSON. If a user shares the backup file, their IPTV provider credentials could be exposed.

**Fix:** Either encrypt the backup file or strip credentials before export, with an option to re-enter them on import.

---

## 8. ✅ N/A — PIN Stored as Hash Without Iteration Count

**File:** `data/src/main/java/com/streamvault/data/preferences/PreferencesRepository.kt`  
**Severity:** 🔵 LOW

The PIN hash uses a single-round hash (assumed from the code pattern). For a 4-digit PIN (only 10,000 possible values), even SHA-256 with salt is brute-forceable in milliseconds.

**Fix:** Use PBKDF2 with high iterations or bcrypt. A 4-digit PIN should have at least 100,000 iterations of PBKDF2 to add meaningful delay.

**Mitigation:** This is a parental control PIN, not a financial credential. The threat model is a child trying to bypass it, not a sophisticated attacker — so the current implementation is acceptable but could be stronger.

---

## ✅ Strong Security Practices (No Action Needed)

| Practice | Status |
|----------|--------|
| **AES-GCM encryption via Keystore** | ✅ Properly implemented in CredentialCrypto |
| **Cleartext traffic disabled** | ✅ `network_security_config.xml` enforces HTTPS |
| **Minimal permissions** | ✅ Only INTERNET + ACCESS_NETWORK_STATE |
| **No exported services** | ✅ Only MainActivity exported with LAUNCHER filter |
| **No WebView** | ✅ Zero WebView attack surface |
| **No clipboard access** | ✅ Credentials never in clipboard |
| **No hardcoded secrets** | ✅ No API keys, tokens, or passwords in source |
| **Content provider safety** | ✅ Only AndroidX startup provider (framework-managed) |
