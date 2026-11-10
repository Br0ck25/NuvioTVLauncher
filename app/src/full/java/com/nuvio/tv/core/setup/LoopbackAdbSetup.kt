package com.nuvio.tv.core.setup

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
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Pairs with this TV's own Wireless debugging daemon over 127.0.0.1 and applies the setup the
 * README otherwise asks the user to run from a computer: the permission grants and, optionally,
 * handing the system Home preference over to Nuvio.
 *
 * The Home handover follows [HomeHandover]: ask the TV what owns Home, switch it, re-resolve to
 * confirm, and put the previous Home back when the switch did not take.
 */
internal class LoopbackAdbSetup(private val context: Context) : LocalAdbSetup {

    override suspend fun setUp(
        pairingPort: Int?,
        pairingCode: String?,
        connectPort: Int?,
        mode: SetupMode,
        disableStockLauncher: Boolean
    ): Result<SetupOutcome> = withContext(Dispatchers.IO) {
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
                applySetup(it, mode, disableStockLauncher)
            }
        }.onFailure { Log.w(TAG, "Loopback ADB setup failed", it) }
    }

    private fun applySetup(
        manager: AbsAdbConnectionManager,
        mode: SetupMode,
        disableStockLauncher: Boolean
    ): SetupOutcome {
        val pkg = context.packageName
        // Required for the setup to survive a reboot or an app update. Judge success by the
        // resulting permission, not by command output (this prints nothing).
        runCatching { shell(manager, HomeHandover.grantWriteSecureSettingsCommand(pkg)) }
        val granted = HomeSetup.hasWriteSecureSettings(context)
        check(granted) {
            "Android did not grant the permission. Wireless debugging may be restricted on this TV."
        }
        // Best effort: unknown on older firmware, and not every TV restricts these settings.
        runCatching { shell(manager, HomeHandover.allowRestrictedSettingsCommand(pkg)) }

        val before = resolveHome(manager)
        if (mode == SetupMode.GRANTS_ONLY) {
            return SetupOutcome(
                grantsApplied = true,
                writeSecureSettingsGranted = granted,
                homeComponent = before,
                homeIsNuvio = HomeHandover.homeIsOurs(before, ourComponent()),
                rolledBack = false
            )
        }

        val plan = HomeHandover.plan(
            ourComponent = ourComponent(),
            ourPackage = pkg,
            homeComponent = before,
            disableStockLauncher = disableStockLauncher
        )
        plan.setHome?.let { runCatching { shell(manager, it) } }
        // Warm the app up before it can be launched by a HOME press.
        runCatching { shell(manager, HomeHandover.compileCommand(pkg)) }

        val after = resolveHome(manager)
        if (HomeHandover.homeIsOurs(after, ourComponent())) {
            val disabled = plan.disable.mapNotNull { command ->
                val output = runCatching { shell(manager, command) }.getOrDefault("")
                val target = command.substringAfterLast(' ')
                // `pm disable-user` prints the new state; anything else means the TV refused.
                if (output.contains("new state: disabled-user", ignoreCase = true)) target else null
            }
            return SetupOutcome(
                grantsApplied = true,
                writeSecureSettingsGranted = granted,
                homeComponent = after,
                homeIsNuvio = true,
                rolledBack = false,
                disabledPackages = disabled,
                recoveryCommands = plan.recovery
            )
        }

        // The switch did not take. Put the TV back the way it was: a Home that does not start can
        // only be rescued over ADB, and this session is the only ADB the user has.
        plan.rollback.forEach { command -> runCatching { shell(manager, command) } }
        val restored = resolveHome(manager)
        if (before == null || restored != before) {
            val recovery = plan.recovery.ifEmpty { listOf(HomeHandover.resolveHomeCommand()) }
            error(
                "Nuvio could not become the Home app and the previous Home app was not restored " +
                    "(Home now resolves to ${restored ?: "nothing"}). From a computer, run: " +
                    recovery.joinToString(" ; ")
            )
        }
        error(
            "This TV kept $before as its Home app. The change was undone. Try the system Home " +
                "settings instead, or re-run with \"Also disable the stock launcher\" ticked."
        )
    }

    /** `pkg/full.activity.Name`, the form `cmd package set-home-activity` and resolve both print. */
    private fun ourComponent(): String =
        "${context.packageName}/${ComponentName(context, MainActivity::class.java).className}"

    private fun resolveHome(manager: AbsAdbConnectionManager): String? {
        val output = runCatching { shell(manager, HomeHandover.resolveHomeCommand()) }.getOrNull()
        return HomeHandover.parseResolvedComponent(output)
            ?.let { HomeHandover.normalizeComponent(it, context.packageName) }
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
