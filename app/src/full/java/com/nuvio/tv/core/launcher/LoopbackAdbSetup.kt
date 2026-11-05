package com.nuvio.tv.core.launcher

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import com.nuvio.tv.MainActivity
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Pairs with this TV's own Wireless debugging daemon over 127.0.0.1 and applies the grants the
 * README otherwise asks the user to run from a computer.
 */
internal class LoopbackAdbSetup(private val context: Context) : LocalAdbSetup {

    override suspend fun setUp(pairingPort: Int?, pairingCode: String?, connectPort: Int?): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val manager = KeyStoreAdbConnectionManager(context)
                manager.use {
                    it.setApi(Build.VERSION.SDK_INT)
                    it.setTimeout(15, TimeUnit.SECONDS)
                    if (pairingPort != null && !pairingCode.isNullOrBlank()) {
                        check(it.pair(pairingPort, pairingCode.trim())) { "Pairing was rejected. Check the code and port." }
                    }
                    val connected = try {
                        if (connectPort != null) it.connect(connectPort) else it.autoConnect(context, 15_000)
                    } catch (e: AdbPairingRequiredException) {
                        throw IllegalStateException("This TV isn't paired with Nuvio yet. Enter the pairing port and code.", e)
                    }
                    check(connected) { "Could not connect to Wireless debugging after pairing." }
                    applyGrants(it)
                }
                LauncherSelfSetup.finish(context)
            }.onFailure { Log.w(TAG, "Loopback ADB setup failed", it) }
        }

    private fun applyGrants(manager: AbsAdbConnectionManager) {
        val pkg = context.packageName
        // Required: lets the app enable its own accessibility service and keep it enabled.
        // Judge success by the resulting permission, not by command output (this prints nothing).
        runCatching { shell(manager, "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS") }
        check(AccessibilityWatchdog.canWriteSecureSettings(context)) {
            "Android did not grant the permission. Wireless debugging may be restricted on this TV."
        }
        // Best effort: unknown on older firmware, and not every TV restricts accessibility.
        runCatching { shell(manager, "appops set $pkg ACCESS_RESTRICTED_SETTINGS allow") }
        val home = ComponentName(context, MainActivity::class.java).flattenToShortString()
        runCatching { shell(manager, "cmd package set-home-activity $home") }

        // Automatically enable Nuvio's Accessibility Service so Home key override works immediately
        val serviceComp = ComponentName(context, LauncherAccessibilityService::class.java).flattenToString()
        val currentServices = runCatching { shell(manager, "settings get secure enabled_accessibility_services") }.getOrNull()?.trim().orEmpty()
        val updatedServices = if (currentServices.isBlank() || currentServices == "null") {
            serviceComp
        } else if (!currentServices.contains(serviceComp)) {
            "$currentServices:$serviceComp"
        } else {
            currentServices
        }
        runCatching { shell(manager, "settings put secure enabled_accessibility_services $updatedServices") }
        runCatching { shell(manager, "settings put secure accessibility_enabled 1") }
        LauncherSetup.allowAccessibilityFallback(context)
    }

    /** Runs a shell command and returns its output, which is often empty. */
    private fun shell(manager: AbsAdbConnectionManager, command: String): String =
        manager.openStream("shell:$command").use { stream ->
            val input = stream.openInputStream()
            val output = StringBuilder()
            val buffer = ByteArray(1024)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.append(String(buffer, 0, count))
                }
            } catch (_: IOException) {
                // libadb-android throws instead of returning -1 when the daemon closes the stream
                // once the command has finished, which is the normal end of every command.
            }
            output.toString()
        }.also { Log.d(TAG, "adb shell $command -> ${it.trim()}") }

    private companion object {
        const val TAG = "LoopbackAdbSetup"
    }
}

/** Persists one RSA identity so the TV recognises this app on later connections. */
private class KeyStoreAdbConnectionManager(context: Context) : AbsAdbConnectionManager() {
    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        val dir = context.noBackupFilesDir
        val keyFile = File(dir, "adb_key.pk8")
        val certFile = File(dir, "adb_cert.der")
        val loaded = runCatching {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            val cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            key to cert
        }.getOrNull()
        val (key, cert) = loaded ?: generate().also { (newKey, newCert) ->
            keyFile.writeBytes(newKey.encoded)
            certFile.writeBytes(newCert.encoded)
        }
        privateKey = key
        certificate = cert
    }

    override fun getPrivateKey(): PrivateKey = privateKey
    override fun getCertificate(): Certificate = certificate
    override fun getDeviceName(): String = "Nuvio TV"

    private fun generate(): Pair<PrivateKey, Certificate> {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
        // Android's built-in "BC" provider has no SHA256withRSA, so build the certificate structure
        // with BouncyCastle's ASN.1 classes and sign it with the platform's default provider.
        val name = X500Name("CN=Nuvio TV")
        val now = System.currentTimeMillis()
        val signatureAlgorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
        val tbs = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(BigInteger.valueOf(now)))
            setIssuer(name)
            setSubject(name)
            setStartDate(Time(Date(now - TimeUnit.DAYS.toMillis(1))))
            setEndDate(Time(Date(now + TimeUnit.DAYS.toMillis(365L * 25))))
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.public.encoded))
            setSignature(signatureAlgorithm)
        }.generateTBSCertificate()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private)
            update(tbs.getEncoded(ASN1Encoding.DER))
            sign()
        }
        val parts = ASN1EncodableVector().apply {
            add(tbs)
            add(signatureAlgorithm)
            add(DERBitString(signature))
        }
        val der = DERSequence(parts).getEncoded(ASN1Encoding.DER)
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der))
        return pair.private to certificate
    }
}
