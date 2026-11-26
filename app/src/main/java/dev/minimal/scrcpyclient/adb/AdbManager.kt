package dev.minimal.scrcpyclient.adb

import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import sun.security.x509.AlgorithmId
import sun.security.x509.CertificateAlgorithmId
import sun.security.x509.CertificateExtensions
import sun.security.x509.CertificateIssuerName
import sun.security.x509.CertificateSerialNumber
import sun.security.x509.CertificateSubjectName
import sun.security.x509.CertificateValidity
import sun.security.x509.CertificateVersion
import sun.security.x509.CertificateX509Key
import sun.security.x509.KeyIdentifier
import sun.security.x509.PrivateKeyUsageExtension
import sun.security.x509.SubjectKeyIdentifierExtension
import sun.security.x509.X500Name
import sun.security.x509.X509CertImpl
import sun.security.x509.X509CertInfo
import java.security.KeyPair
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.Date
import java.util.Random

/** libadb-android connection manager using the key from [AdbKeyStore]. */
class AdbManager(keys: AdbKeyStore) : AbsAdbConnectionManager() {

    private val pk: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val kp = keys.loadKeyPair()
        pk = kp.private
        cert = buildCertificate(kp)
    }

    override fun getPrivateKey(): PrivateKey = pk
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "scrcpy-client"

    private fun buildCertificate(kp: KeyPair): Certificate {
        val algorithm = "SHA512withRSA"
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + 10L * 365 * 86_400_000)
        val ext = CertificateExtensions()
        ext.set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(kp.public).identifier))
        ext.set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
        val name = X500Name("CN=scrcpy-client")
        val info = X509CertInfo()
        info.set("version", CertificateVersion(2))
        info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
        info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
        info.set("subject", CertificateSubjectName(name))
        info.set("key", CertificateX509Key(kp.public))
        info.set("validity", CertificateValidity(notBefore, notAfter))
        info.set("issuer", CertificateIssuerName(name))
        info.set("extensions", ext)
        val impl = X509CertImpl(info)
        impl.sign(kp.private, algorithm)
        return impl
    }
}
