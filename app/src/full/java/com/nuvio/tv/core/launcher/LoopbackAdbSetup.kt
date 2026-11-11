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

    override suspend fun run(action: DeviceAction, connectPort: Int?): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                KeyStoreAdbConnectionManager(context).use { manager ->
                    manager.setApi(Build.VERSION.SDK_INT)
                    manager.setTimeout(15, TimeUnit.SECONDS)
                    val connected = try {
                        if (connectPort != null) manager.connect(connectPort) else manager.autoConnect(context, 15_000)
                    } catch (e: AdbPairingRequiredException) {
                        throw IllegalStateException("This TV isn't paired yet. Pair it first (the top box on this page).", e)
                    }
                    check(connected) { "Could not reach Wireless debugging. Is it still switched on?" }
                    perform(manager, action)
                }
            }.onFailure { Log.w(TAG, "Loopback ADB action $action failed", it) }
        }

    private fun perform(manager: AbsAdbConnectionManager, action: DeviceAction): String = when (action) {
        DeviceAction.MAKE_HOME -> {
            makeHome(manager)
            // The Home setting can take a moment to show up, so look again before saying it didn't take.
            val took = (0 until 8).any { attempt ->
                if (attempt > 0) Thread.sleep(500)
                LauncherSetup.isDefaultHome(context)
            }
            if (took) "Done. The Home button now opens this launcher."
            else "The command ran, but this TV's own launcher is still the Home app (Google TV does that). Press \"Turn on Home button override\" so Home still opens this launcher, or \"Turn off the stock launcher\" to remove the other one."
        }
        DeviceAction.ENABLE_NOTIFICATIONS -> {
            allowNotifications(manager)
            if (com.nuvio.tv.core.notification.LauncherNotificationListenerService.isAccessGranted(context)) "Notifications are on."
            else "The command ran, but Android didn't turn notifications on. Open Settings → Apps → Special access → Notification access and switch this launcher on."
        }
        DeviceAction.ENABLE_OVERRIDE -> {
            enableOverride(manager)
            "Home button override is on. It stays on after a restart."
        }
        DeviceAction.CHECK_HOME -> {
            val pkg = DeviceCommands.resolvedHomePackage(shell(manager, DeviceCommands.resolveHome))
            when {
                pkg == null -> "Could not tell which launcher is Home."
                pkg == context.packageName -> "This launcher is the Home app."
                else -> "The Home button opens $pkg, not this launcher."
            }
        }
        DeviceAction.DISABLE_STOCK -> {
            DeviceCommands.disableStock.forEach { shell(manager, it) }
            "Stock launcher turned off. Turn it back on from here if anything misbehaves."
        }
        DeviceAction.ENABLE_STOCK -> {
            DeviceCommands.enableStock.forEach { shell(manager, it) }
            "Stock launcher turned back on."
        }
        DeviceAction.RESTORE_ORIGINAL -> {
            val other = DeviceCommands.otherHomeComponent(shell(manager, DeviceCommands.listHomes), context.packageName)
            if (other == null) {
                "No other launcher found on this TV (the stock one may be turned off; turn it back on first)."
            } else {
                shell(manager, DeviceCommands.setHome(other))
                "The Home button now opens $other."
            }
        }
    }

    private fun applyGrants(manager: AbsAdbConnectionManager) {
        enableOverride(manager)
        makeHome(manager)
        allowNotifications(manager)
    }

    /** Notification access normally needs a trip through Settings; the shell can grant it directly. */
    private fun allowNotifications(manager: AbsAdbConnectionManager) {
        val listener = ComponentName(context, com.nuvio.tv.core.notification.LauncherNotificationListenerService::class.java).flattenToString()
        runCatching { shell(manager, DeviceCommands.allowNotificationListener(listener)) }
    }

    private fun makeHome(manager: AbsAdbConnectionManager) {
        val home = ComponentName(context, MainActivity::class.java).flattenToShortString()
        runCatching { shell(manager, DeviceCommands.setHome(home)) }
    }

    /** Lets the app enable its own accessibility service, keeps it enabled, and turns it on now. */
    private fun enableOverride(manager: AbsAdbConnectionManager) {
        val pkg = context.packageName
        // Required: lets the app enable its own accessibility service and keep it enabled.
        // Judge success by the resulting permission, not by command output (this prints nothing).
        runCatching { shell(manager, "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS") }
        check(AccessibilityWatchdog.canWriteSecureSettings(context)) {
            "Android did not grant the permission. Wireless debugging may be restricted on this TV."
        }
        // Best effort: unknown on older firmware, and not every TV restricts accessibility.
        runCatching { shell(manager, "appops set $pkg ACCESS_RESTRICTED_SETTINGS allow") }
        val service = ComponentName(context, LauncherAccessibilityService::class.java).flattenToString()
        val current = runCatching { shell(manager, "settings get secure enabled_accessibility_services") }.getOrNull()
        runCatching { shell(manager, "settings put secure enabled_accessibility_services ${DeviceCommands.withAccessibilityService(current, service)}") }
        runCatching { shell(manager, "settings put secure accessibility_enabled 1") }
        LauncherSetup.allowAccessibilityFallback(context)
        LauncherSelfSetup.finish(context)
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
