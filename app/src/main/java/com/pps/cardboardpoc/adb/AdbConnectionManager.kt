package com.pps.cardboardpoc.adb

import android.content.Context
import android.os.Build
import android.sun.misc.BASE64Encoder
import android.sun.security.provider.X509Factory
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random

/**
 * Identidad adb de CeluVR: el par de claves RSA y el certificado con que la app se autentica contra el
 * adbd local (depuración inalámbrica). Se generan una vez y se guardan en el almacenamiento privado,
 * para que el emparejamiento siga valiendo entre arranques. Portado del ejemplo de libadb-android.
 */
class AdbConnectionManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val stored = readPrivateKey(context)
        if (stored != null) {
            privateKey = stored
            certificate = readCertificate(context)
                ?: throw IllegalStateException("falta el certificado adb")
        } else {
            val generator = KeyPairGenerator.getInstance("RSA").apply {
                initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
            }
            val pair = generator.generateKeyPair()
            privateKey = pair.private
            certificate = generateCertificate(pair.public, privateKey)
            writePrivateKey(context, privateKey)
            writeCertificate(context, certificate)
        }
    }

    override fun getPrivateKey(): PrivateKey = privateKey

    override fun getCertificate(): Certificate = certificate

    override fun getDeviceName(): String = "CeluVR"

    companion object {
        private const val KEY_FILE = "adb_private.key"
        private const val CERT_FILE = "adb_cert.pem"

        private fun generateCertificate(publicKey: PublicKey, privateKey: PrivateKey): Certificate {
            val subject = "CN=CeluVR"
            val algorithm = "SHA512withRSA"
            val notBefore = Date()
            val notAfter = Date(System.currentTimeMillis() + 86_400_000L)

            val extensions = CertificateExtensions()
            extensions.set(
                "SubjectKeyIdentifier",
                SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier)
            )
            extensions.set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))

            val name = X500Name(subject)
            val info = X509CertInfo()
            info.set("version", CertificateVersion(2))
            info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
            info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
            info.set("subject", CertificateSubjectName(name))
            info.set("key", CertificateX509Key(publicKey))
            info.set("validity", CertificateValidity(notBefore, notAfter))
            info.set("issuer", CertificateIssuerName(name))
            info.set("extensions", extensions)

            return X509CertImpl(info).apply { sign(privateKey, algorithm) }
        }

        private fun readPrivateKey(context: Context): PrivateKey? {
            val file = File(context.filesDir, KEY_FILE)
            if (!file.exists()) return null
            val spec = PKCS8EncodedKeySpec(file.readBytes())
            return KeyFactory.getInstance("RSA").generatePrivate(spec)
        }

        private fun writePrivateKey(context: Context, privateKey: PrivateKey) {
            File(context.filesDir, KEY_FILE).writeBytes(privateKey.encoded)
        }

        private fun readCertificate(context: Context): Certificate? {
            val file = File(context.filesDir, CERT_FILE)
            if (!file.exists()) return null
            file.inputStream().use {
                return CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
        }

        private fun writeCertificate(context: Context, certificate: Certificate) {
            File(context.filesDir, CERT_FILE).outputStream().use { os ->
                os.write(X509Factory.BEGIN_CERT.toByteArray(StandardCharsets.UTF_8))
                os.write('\n'.code)
                BASE64Encoder().encode(certificate.encoded, os)
                os.write('\n'.code)
                os.write(X509Factory.END_CERT.toByteArray(StandardCharsets.UTF_8))
            }
        }

        @Volatile
        private var instance: AdbConnectionManager? = null

        fun getInstance(context: Context): AdbConnectionManager =
            instance ?: synchronized(this) {
                instance ?: AdbConnectionManager(context.applicationContext).also { instance = it }
            }
    }
}
