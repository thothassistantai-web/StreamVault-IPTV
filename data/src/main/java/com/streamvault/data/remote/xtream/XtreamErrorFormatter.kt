package com.streamvault.data.remote.xtream

import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException

internal object XtreamErrorFormatter {
    fun message(prefix: String, throwable: Throwable): String {
        return if (throwable.isCertificateTrustFailure()) {
            "$prefix: Server TLS certificate is not trusted by this device. Verify the HTTPS URL or ask the provider for a valid certificate."
        } else {
            "$prefix: ${throwable.message ?: "Unexpected network error"}"
        }
    }

    private fun Throwable.isCertificateTrustFailure(): Boolean {
        return generateSequence(this) { it.cause }.any { current ->
            current is SSLPeerUnverifiedException ||
                current is CertificateException ||
                current.message?.contains("trust anchor", ignoreCase = true) == true ||
                current.message?.contains("certificate", ignoreCase = true) == true ||
                current.message?.contains("hostname", ignoreCase = true) == true
        }
    }
}