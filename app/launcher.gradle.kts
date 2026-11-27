// Launcher layer's Gradle settings, applied from the end of build.gradle.kts (see LAUNCHER_RULES.md).
// Nuvio One's build.gradle.kts keeps its own values; this script overrides what the launcher changes,
// so a Nuvio One update to build.gradle.kts rarely conflicts with us.
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension

val launcherAndroid = extensions.getByType<ApplicationExtension>()

// Where the in-app updater looks for new releases (-PlivetvUpdateRepo=<owner/repo> still overrides).
val launcherUpdateRepo = (findProperty("livetvUpdateRepo") as String?)
    ?.trim()
    ?.takeIf { it.isNotBlank() }
    ?: "Br0ck25/NuvioTVLauncher"
launcherAndroid.defaultConfig.buildConfigField("String", "GITHUB_OWNER", "\"${launcherUpdateRepo.substringBefore('/', "")}\"")
launcherAndroid.defaultConfig.buildConfigField("String", "GITHUB_REPO", "\"${launcherUpdateRepo.substringAfter('/', "")}\"")

// App ids. These run after build.gradle.kts's own onVariants callbacks, so the last set() wins.
extensions.getByType<ApplicationAndroidComponentsExtension>().apply {
    onVariants(selector().withBuildType("debug")) { variant ->
        val isPlaystore = variant.productFlavors.any { it.second == "playstore" }
        // -PlivetvDebugAppId=<id> installs a debug build next to another copy (own data, own icon).
        val debugAppId = (findProperty("livetvDebugAppId") as String?)?.takeIf { it.isNotBlank() } ?: "com.nuvio.tv.launcher"
        variant.applicationId.set(if (isPlaystore) "com.nuvio.appdebug" else debugAppId)
    }
    onVariants(selector().withBuildType("release")) { variant ->
        val isPlaystore = variant.productFlavors.any { it.second == "playstore" }
        // Test builds (-PlivetvTestBuild=true) get their own ID, so they install next to the real app.
        val testBuild = (findProperty("livetvTestBuild") as String?)?.toBoolean() == true
        if (!isPlaystore) variant.applicationId.set(if (testBuild) "com.nuvio.tv.launcher.test" else "com.nuvio.tv.launcher")
    }
}

// The official Nuvio account server and its publishable (public by design) client key, used when a build
// doesn't set its own NUVIO_SUPABASE_URL / NUVIO_SUPABASE_ANON_KEY, so signing in with a real Nuvio account
// works without any extra setup. Same lookup order as build.gradle.kts: debug reads local.dev.properties then
// local.properties; release reads the environment, then local.properties.
val launcherDefaultSupabaseUrl = "https://api.nuvio.tv"
val launcherDefaultSupabaseAnonKey = "sb_publishable_1Clq8rlTVACkdcZuqr6_AD__xUUC_EN"
fun launcherProps(fileName: String): java.util.Properties = java.util.Properties().apply {
    val file = rootProject.file(fileName)
    if (file.exists()) file.inputStream().use { load(it) }
}
val launcherLocalProps = launcherProps("local.properties")
val launcherDevProps = launcherProps("local.dev.properties")
fun launcherSupabase(key: String, fallback: String, vararg sources: (String) -> String?): String =
    sources.firstNotNullOfOrNull { source -> source(key)?.trim()?.takeIf { it.isNotBlank() } } ?: fallback
launcherAndroid.buildTypes.getByName("debug") {
    val fromDev = { key: String -> launcherDevProps.getProperty(key) }
    val fromLocal = { key: String -> launcherLocalProps.getProperty(key) }
    buildConfigField("String", "SUPABASE_URL", "\"${launcherSupabase("NUVIO_SUPABASE_URL", launcherDefaultSupabaseUrl, fromDev, fromLocal)}\"")
    buildConfigField("String", "SUPABASE_ANON_KEY", "\"${launcherSupabase("NUVIO_SUPABASE_ANON_KEY", launcherDefaultSupabaseAnonKey, fromDev, fromLocal)}\"")
}
launcherAndroid.buildTypes.getByName("release") {
    val fromEnv = { key: String -> System.getenv(key) }
    val fromLocal = { key: String -> launcherLocalProps.getProperty(key) }
    buildConfigField("String", "SUPABASE_URL", "\"${launcherSupabase("NUVIO_SUPABASE_URL", launcherDefaultSupabaseUrl, fromEnv, fromLocal)}\"")
    buildConfigField("String", "SUPABASE_ANON_KEY", "\"${launcherSupabase("NUVIO_SUPABASE_ANON_KEY", launcherDefaultSupabaseAnonKey, fromEnv, fromLocal)}\"")
}

dependencies {
    // On-device ADB client used by the phone-driven Home button setup (runs the commands on this TV).
    add("fullImplementation", "com.github.MuntashirAkon:libadb-android:3.0.0")
    // libadb-android only exposes BouncyCastle at runtime; used to create the ADB identity cert.
    add("fullImplementation", "org.bouncycastle:bcprov-jdk15to18:1.78")
    add("testImplementation", "org.robolectric:robolectric:4.17")
    // Icon pack tests parse appfilter.xml; Android's XmlPullParser is not on the JVM test classpath.
    add("testImplementation", "net.sf.kxml:kxml2:2.3.0")
}

// Nuvio One's SMB library (jcifs-ng) pulls org.bouncycastle:bcprov-jdk18on:1.76, the same classes as the
// bcprov-jdk15to18:1.78 above. Two copies fail the duplicate-class check, so the full flavor keeps the newer one.
// (The playstore flavor has no launcher libraries and keeps jcifs-ng's own copy.)
configurations.matching { it.name.startsWith("full") }.configureEach {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
}

// Our manifest entries (HOME / ALL_APPS filters, services, boot receiver, app queries) live in
// src/launcher/AndroidManifest.xml. It is merged for every build type, because a source set takes one manifest.
launcherAndroid.buildTypes.configureEach {
    launcherAndroid.sourceSets.maybeCreate(name).manifest.srcFile("src/launcher/AndroidManifest.xml")
}
