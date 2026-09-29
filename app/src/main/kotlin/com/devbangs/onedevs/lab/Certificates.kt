package com.devbangs.onedevs.lab

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * A signing certificate from its DER bytes, which is what Android's Signature
 * objects hold.
 *
 * java.security rather than anything of Android's, so the parsing runs in a
 * unit test against a real certificate.
 */
internal object Certificates {

    fun of(der: ByteArray): Certificate? = runCatching {
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        Certificate(
            subject = cert.subjectX500Principal.name,
            issuer = cert.issuerX500Principal.name,
            serial = cert.serialNumber.toString(16).uppercase(),
            notBefore = cert.notBefore.time,
            notAfter = cert.notAfter.time,
            algorithm = cert.sigAlgName,
            sha256 = fingerprint("SHA-256", der),
            sha1 = fingerprint("SHA-1", der),
        )
    }.getOrNull()

    fun fingerprint(algorithm: String, der: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(der).joinToString(":") { "%02X".format(it) }
}
