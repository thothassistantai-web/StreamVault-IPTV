# StreamVault Pre-Release Audit: Security Review

> **Audit Date:** March 17, 2026  
> **Focus:** OWASP Top 10, credential handling, network security, data protection

---

## Overview

The app demonstrates strong security fundamentals (AES-GCM credential encryption, PBKDF2 PIN hashing, input sanitization). However, several gaps must be addressed for production hardening.

---

## SEC-1: No Certificate Pinning [MEDIUM-HIGH]

**Location:** `app/src/main/java/com/streamvault/app/di/NetworkModule.kt`

**Description:**  
OkHttp client accepts any CA-signed certificate. No certificate pinning is configured for any connections.

**Risk:** Man-in-the-middle attacks on Xtream API login requests could intercept credentials even over HTTPS.

**Recommendation:**  
Certificate pinning for IPTV providers is complex (providers use many different servers/domains) but at minimum the app should:
1. Log certificate fingerprints on first connection
2. Warn users if a certificate changes unexpectedly
3. Consider Trust-on-First-Use (TOFU) model

---

## SEC-2: Global Cleartext HTTP Permitted [MEDIUM]

**Location:** `app/src/main/res/xml/network_security_config.xml`

```xml
<base-config cleartextTrafficPermitted="true">
```

**Description:**  
HTTP traffic is permitted globally. While necessary for IPTV stream URLs (many providers use HTTP), this means API/control traffic could also use HTTP in edge cases.

**Current Mitigation:** `UrlSecurityPolicy.validateXtreamServerUrl()` enforces HTTPS for Xtream API URLs.

**Recommendation:**  
The mitigation is adequate for Xtream, but document clearly why cleartext is globally enabled and ensure all non-stream network calls enforce HTTPS at the code level.

---

## SEC-3: Database Not Encrypted at Rest [MEDIUM]

**Location:** `data/src/main/java/com/streamvault/data/local/StreamVaultDatabase.kt`

**Description:**  
Room database is stored as plaintext SQLite. While credentials are encrypted separately via `CredentialCrypto`, the database still contains:
- Provider server URLs and usernames (in the providers table)
- Complete channel/movie/series catalogs
- Viewing history and favorites
- Parental control category flags

**Recommendation:**  
Consider SQLCipher integration for database-level encryption. Evaluate trade-off against performance impact (SQLCipher adds ~5-15% overhead).

---

## SEC-4: PIN Stored as String in Memory [LOW]

**Location:** `data/src/main/java/com/streamvault/data/preferences/PreferencesRepository.kt`

**Description:**  
Parental control PIN is received as `String` parameter and processed through PBKDF2. While hashing is correct (120k iterations, random salt), the PIN string itself remains in JVM heap memory until garbage collected.

**Recommendation:**  
Accept `CharArray` instead of `String`, zero it after hashing. Low risk on Android TV (physical access needed), but best practice.

---

## SEC-5: No Key Rotation Mechanism [LOW]

**Location:** `data/src/main/java/com/streamvault/data/security/CredentialCrypto.kt`

**Description:**  
The encryption key (`KEY_ALIAS = "streamvault_credentials"`) has no rotation mechanism. If the Android Keystore is compromised, all stored credentials remain exposed until the user manually re-enters them.

**Recommendation:**  
Add a key rotation function that re-encrypts all credentials with a new key, triggered periodically or on security events.

---

## SEC-6: GSON Reflection Still in Use [LOW]

**Location:** `gradle/libs.versions.toml`, various DTO classes

**Description:**  
GSON uses runtime reflection for (de)serialization, which:
1. Can bypass ProGuard obfuscation if rules aren't perfectly configured
2. Exposes class structure at runtime
3. Conflicts with the app's shift to `kotlinx.serialization` (used in `OkHttpXtreamApiService`)

**Recommendation:**  
Complete migration from GSON to `kotlinx.serialization` for consistency and better obfuscation support.

---

## SEC-7: HTTP Logging Could Leak Sensitive Data [LOW]

**Location:** `app/src/main/java/com/streamvault/app/di/NetworkModule.kt`

**Description:**  
`HttpLoggingInterceptor` is configured at `BASIC` level in releases, which logs URLs. Xtream API URLs contain credentials in query parameters (`username`, `password`).

**Current Mitigation:** `XtreamUrlFactory.sanitizeLogMessage()` is used. Verify it covers all code paths.

**Recommendation:**  
Ensure `sanitizeLogMessage()` is applied to ALL OkHttp logging, not just explicit calls. Consider stripping query parameters entirely from logs.

---

## SEC-8: Missing CI/CD Security Pipeline [MEDIUM]

**Location:** `.github/workflows/android.yml`

**Description:**  
The CI/CD pipeline runs only `assembleDebug` and unit tests. Missing:
- Secret scanning (TruffleHog or similar)
- Dependency vulnerability scanning (OWASP dependency-check)
- Lint security checks
- Release build validation with ProGuard
- Code coverage reporting

**Recommendation:**  
Add security-focused CI steps before first production release.

---

## Security Strengths ✅

The following security practices are well-implemented:

| Feature | Implementation | Assessment |
|---------|---------------|------------|
| **Credential Encryption** | AES-256-GCM with Android Keystore, randomized 12-byte IV | Excellent |
| **PIN Hashing** | PBKDF2-SHA256, 120k iterations, 16-byte random salt | Industry standard |
| **Legacy PIN Migration** | Auto-migrates plaintext PIN to hashed on next verify | Good hygiene |
| **Input Sanitization** | `ProviderInputSanitizer` enforces length limits, removes control chars | Solid |
| **URL Validation** | `UrlSecurityPolicy` validates scheme, checks CRLF injection | Comprehensive |
| **SQL Injection Prevention** | Room parameterized queries throughout; FTS queries properly escaped | Excellent |
| **Manifest Permissions** | Minimal: INTERNET + NETWORK_STATE only | Best practice |
| **Backup Prevention** | `android:allowBackup="false"` | Correct for security-sensitive app |
| **Xtream URL Sanitization** | Credentials stripped from log output | Good practice |
