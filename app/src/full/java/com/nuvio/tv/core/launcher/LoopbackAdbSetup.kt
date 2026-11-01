package com.nuvio.tv.core.launcher

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import com.nuvio.tv.MainActivity
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x509.X509Name
import org.bouncycastle.x509.X509V3CertificateGenerator
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
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

    override suspend fun setUp(pairingPort: Int, pairingCode: String, connectPort: Int?): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val manager = KeyStoreAdbConnectionManager(context)
                manager.use {
                    it.setApi(Build.VERSION.SDK_INT)
                    it.setTimeout(15, TimeUnit.SECONDS)
                    check(it.pair(pairingPort, pairingCode.trim())) { "Pairing was rejected. Check the code and port." }
                    val connected = if (connectPort != null) it.connect(connectPort)
                    else it.autoConnect(context, 15_000)
                    check(connected) { "Could not connect to Wireless debugging after pairing." }
                    applyGrants(it)
                }
                LauncherSelfSetup.finish(context)
            }.onFailure { Log.w(TAG, "Loopback ADB setup failed", it) }
        }

    private fun applyGrants(manager: AbsAdbConnectionManager) {
        val pkg = context.packageName
        // Required: lets the app enable its own accessibility service and keep it enabled.
        shell(manager, "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
        check(AccessibilityWatchdog.canWriteSecureSettings(context)) {
            "Android did not grant the permission. Wireless debugging may be restricted on this TV."
        }
        // Best effort: unknown on older firmware, and not every TV restricts accessibility.
        shell(manager, "appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
        val home = ComponentName(context, MainActivity::class.java).flattenToShortString()
        shell(manager, "cmd package set-home-activity $home")
    }

    private fun shell(manager: AbsAdbConnectionManager, command: String): String =
        manager.openStream("shell:$command").use { stream ->
            stream.openInputStream().bufferedReader().readText()
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
        val name = X509Name("CN=Nuvio TV")
        val generator = X509V3CertificateGenerator().apply {
            setSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            setIssuerDN(name)
            setSubjectDN(name)
            setNotBefore(Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)))
            setNotAfter(Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(365L * 25)))
            setPublicKey(pair.public)
            setSignatureAlgorithm("SHA256withRSA")
        }
        return pair.private to generator.generateX509Certificate(pair.private)
    }
}
