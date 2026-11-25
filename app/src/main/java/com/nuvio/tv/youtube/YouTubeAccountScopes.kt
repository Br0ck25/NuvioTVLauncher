package com.nuvio.tv.youtube

import android.content.Context
import android.util.Log
import com.liskovsoft.googlecommon.service.oauth.YouTubeAccount
import com.liskovsoft.mediaserviceinterfaces.ServiceManager
import com.liskovsoft.mediaserviceinterfaces.oauth.Account
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import com.liskovsoft.youtubeapi.service.YouTubeSignInService
import java.security.MessageDigest

/** The parts of the app that sign in to YouTube. Each has its own accounts, profiles and sign-in. */
enum class AccountScope(internal val tag: String) {
    ONETUBE("onetube"),
    MUSIC("music")
}

/**
 * OneTube and Music keep fully separate YouTube sign-ins, even for the same Google account, and each Nuvio profile
 * has its own of both.
 *
 * The YouTube engine holds one set of accounts at a time, so each (part, Nuvio profile) pair gets its own complete
 * set, kept here as a saved copy. Before a part uses the engine, [ensureHolder] makes the engine hold that pair's set:
 * the set the engine was holding is saved first, then the wanted set is loaded. A sign-in, a profile choice or a
 * sign-out in one part or one Nuvio profile therefore never touches another.
 *
 * Profile 1 takes over the sign-ins that were there before profiles existed, so nobody is signed out by the update.
 * Other profiles start signed out until they sign in.
 */
object YouTubeAccountScopes {
    private const val TAG = "AccountScopes"
    private const val PREFS = "youtube_account_scopes_v3"
    private const val INITIALISED = "initialised"
    private const val PROFILE_KEYS = "profile_keys"
    private const val ACTIVE = "active"

    private val lock = Any()

    /** Which saved set the engine holds right now ("<part>.<profile>"), null until this process has switched. */
    @Volatile
    private var engineHolder: String? = null

    /** The Nuvio profile in use. ProfileManager keeps it up to date. */
    @Volatile
    private var profileId: Int = 1

    /** Called when the active Nuvio profile changes. The engine switches to that profile's sign-in on its next use. */
    fun setProfile(id: Int) {
        profileId = id
    }

    private fun holderOf(scope: AccountScope, profile: Int = profileId) = "${scope.tag}.$profile"

    private fun blobKey(holder: String) = "blob_$holder"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun signIn(): YouTubeSignInService? =
        runCatching { YouTubeServiceManager.instance().signInService as? YouTubeSignInService }.getOrNull()

    /** A stable id for an account or profile in a list: a hash of its sign-in token and page, never the token itself. */
    fun keyOf(account: Account): String {
        val youTube = account as? YouTubeAccount
        val raw = if (youTube != null) "${youTube.refreshToken.orEmpty()}|${youTube.pageIdToken.orEmpty()}"
        else "${account.email.orEmpty()}|${account.name.orEmpty()}"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).take(12)
            .joinToString("") { "%02x".format(it) }
    }

    /** What the engine holds, for the log: counts and yes/no flags only, never names, emails or tokens. */
    fun describe(context: Context): String {
        val list = runCatching { signIn()?.accounts.orEmpty().filterNotNull() }.getOrDefault(emptyList())
        val rows = list.joinToString(" | ") { a ->
            val y = a as? YouTubeAccount
            "${keyOf(a).take(6)} sel=${a.isSelected} token=${!y?.refreshToken.isNullOrBlank()} page=${!y?.pageIdToken.isNullOrBlank()}"
        }
        val held = prefs(context).getString(ACTIVE, null)
        return "engineHolder=$engineHolder held=$held accounts=${list.size} [$rows]"
    }

    /** The engine, holding [scope]'s accounts for the active profile. Blocking; call off the main thread when the set may change. */
    fun engine(context: Context, scope: AccountScope): ServiceManager {
        GlobalPreferences.instance(context.applicationContext)
        ensure(context, scope)
        return YouTubeServiceManager.instance()
    }

    /** Makes the engine hold [scope]'s accounts for the active profile if it doesn't already. */
    fun ensure(context: Context, scope: AccountScope) {
        ensureHolder(context, holderOf(scope))
    }

    private fun ensureHolder(context: Context, want: String) {
        if (engineHolder == want) return
        synchronized(lock) {
            if (engineHolder == want) return
            switchLocked(context, want)
        }
    }

    /** The accounts and profiles of [scope] for the active profile, with the one in use marked as selected. */
    fun accounts(context: Context, scope: AccountScope): List<Account> {
        GlobalPreferences.instance(context.applicationContext)
        ensure(context, scope)
        return runCatching { signIn()?.accounts.orEmpty().filterNotNull() }.getOrDefault(emptyList())
    }

    /** [scope] uses the profile with this [key]. */
    fun select(context: Context, scope: AccountScope, key: String) {
        synchronized(lock) {
            ensureHolder(context, holderOf(scope))
            val account = accounts(context, scope).firstOrNull { keyOf(it) == key } ?: return
            runCatching { signIn()?.selectAccount(account) }.onFailure { Log.w(TAG, "couldn't switch profile", it) }
            runCatching { YouTubeServiceManager.instance().invalidateCache() }
            saveLocked(context)
            Log.i(TAG, "select $scope: ${describe(context)}")
        }
    }

    /** [scope] signed in (or its accounts changed in the engine): keep the saved copy up to date. */
    fun save(context: Context, scope: AccountScope) {
        synchronized(lock) {
            // A sign-in finishes on its own thread and OneTube may have used the engine in the
            // meantime. Make sure the engine really holds this set before reading its export,
            // otherwise the new sign-in was silently dropped from the saved copy.
            ensureHolder(context, holderOf(scope))
            saveLocked(context)
        }
    }

    /**
     * [scope] signs out of the Google login behind its selected profile, which takes that login's other profiles with
     * it. The other parts and other Nuvio profiles keep their own copies.
     */
    fun signOut(context: Context, scope: AccountScope) {
        synchronized(lock) {
            ensureHolder(context, holderOf(scope))
            val svc = signIn() ?: return
            val all = accounts(context, scope)
            val selected = all.firstOrNull { it.isSelected } ?: all.firstOrNull() ?: return
            val token = (selected as? YouTubeAccount)?.refreshToken
            all.filter { it === selected || (token != null && (it as? YouTubeAccount)?.refreshToken == token) }
                .forEach { runCatching { svc.removeAccount(it) }.onFailure { e -> Log.w(TAG, "couldn't remove account", e) } }
            selectFirstIfNoneLocked(svc)
            runCatching { YouTubeServiceManager.instance().invalidateCache() }
            // This empty export is intentional: the user asked for this scope to sign out.
            saveLocked(context, allowEmpty = true)
        }
    }

    /** Forgets the saved sign-ins of a deleted Nuvio profile. */
    fun removeProfile(context: Context, profile: Int) {
        synchronized(lock) {
            val ed = prefs(context).edit()
            for (scope in AccountScope.entries) ed.remove(blobKey(holderOf(scope, profile)))
            ed.apply()
        }
    }

    private fun saveLocked(context: Context, allowEmpty: Boolean = false) {
        val holder = engineHolder ?: return
        val svc = signIn() ?: return
        val exported = svc.exportAccounts()
        if (exported.isBlank() && !allowEmpty) {
            // A failed token refresh makes the engine report no accounts for a moment. Writing
            // that empty list over the saved copy is what turned a temporary sign-in error into
            // a permanent sign-out for both OneTube and Music.
            Log.w(TAG, "not overwriting $holder's saved accounts with an empty export")
            return
        }
        // Refreshed tokens and account details arrive while a part is in use; keep its saved
        // copy current too, so the next launch doesn't restore an older token.
        if (prefs(context).getString(blobKey(holder), null) == exported) return
        prefs(context).edit().putString(blobKey(holder), exported).apply()
    }

    /**
     * Renews [scope]'s access token, restoring its saved account copy first if the engine has
     * none. Returns true when an Authorization header is available afterwards.
     *
     * Called when account data fails to load: the engine can still contain an account object
     * with a token that can no longer be refreshed, so "signed in" alone isn't enough.
     */
    fun recover(context: Context, scope: AccountScope): Boolean {
        synchronized(lock) {
            val want = holderOf(scope)
            ensureHolder(context, want)
            val svc = signIn() ?: return false

            fun authorized(): Boolean {
                svc.invalidateCache()
                runCatching { svc.checkAuth() }
                // printDebugInfo() reports header=ok only after an access token was obtained.
                val info = runCatching { svc.printDebugInfo() }.getOrDefault("")
                return info.contains("header=ok")
            }

            if (authorized()) {
                saveLocked(context)
                Log.i(TAG, "recover $want: token refreshed")
                return true
            }

            val savedBlob = prefs(context).getString(blobKey(want), null)
            if (savedBlob.isNullOrEmpty()) return false
            Log.i(TAG, "recover $want: restoring saved accounts")
            svc.importAccounts(savedBlob)
            selectFirstIfNoneLocked(svc)
            if (authorized()) {
                saveLocked(context)
                Log.i(TAG, "recover $want: restored and refreshed")
                return true
            }
            return false
        }
    }

    private fun selectFirstIfNoneLocked(svc: YouTubeSignInService) {
        val list = runCatching { svc.accounts.orEmpty().filterNotNull() }.getOrDefault(emptyList())
        if (list.isNotEmpty() && list.none { it.isSelected }) {
            runCatching { svc.selectAccount(list.first()) }
        }
    }

    /** Puts [blob] into the engine, or clears the engine when there is nothing saved for this set. */
    private fun loadLocked(context: Context, svc: YouTubeSignInService, blob: String?) {
        if (blob.isNullOrEmpty()) {
            // Nothing saved for this profile: it starts signed out, not with the previous profile's accounts.
            runCatching { svc.accounts.orEmpty().filterNotNull() }.getOrDefault(emptyList())
                .forEach { runCatching { svc.removeAccount(it) } }
        } else {
            svc.importAccounts(blob)
        }
        selectFirstIfNoneLocked(svc)
        runCatching { YouTubeServiceManager.instance().invalidateCache() }
    }

    /** Saved copies from before profiles existed belong to profile 1. Runs once. */
    private fun migrateLocked(context: Context, p: android.content.SharedPreferences, svc: YouTubeSignInService) {
        val ed = p.edit()
        if (!p.getBoolean(INITIALISED, false)) {
            // First run: the accounts already signed in are the original set, so both parts start with a copy of it.
            val current = svc.exportAccounts()
                .ifEmpty { GlobalPreferences.instance(context.applicationContext).mediaServiceAccountData.orEmpty() }
            ed.putString(blobKey(holderOf(AccountScope.ONETUBE, 1)), current)
                .putString(blobKey(holderOf(AccountScope.MUSIC, 1)), current)
                .putString(ACTIVE, holderOf(AccountScope.ONETUBE, 1))
                .putBoolean(INITIALISED, true)
        } else {
            for (scope in AccountScope.entries) {
                p.getString("blob_${scope.tag}", null)?.let { ed.putString(blobKey(holderOf(scope, 1)), it) }
            }
            p.getString(ACTIVE, null)?.takeIf { !it.contains('.') }?.let { ed.putString(ACTIVE, "$it.1") }
        }
        ed.putBoolean(PROFILE_KEYS, true).apply()
    }

    private fun switchLocked(context: Context, want: String) {
        GlobalPreferences.instance(context.applicationContext)
        val svc = signIn() ?: return
        val p = prefs(context)
        if (!p.getBoolean(PROFILE_KEYS, false)) migrateLocked(context, p, svc)
        val held = p.getString(ACTIVE, null) ?: holderOf(AccountScope.ONETUBE, 1)
        if (held != want) {
            // Keep what the engine holds (a sign-in, a profile choice, refreshed names) under its owner, then load ours.
            // Early in a launch the engine may not have read its saved accounts yet; never overwrite a copy with that.
            svc.exportAccounts().takeIf { it.isNotEmpty() }?.let { p.edit().putString(blobKey(held), it).apply() }
            val savedBlob = p.getString(blobKey(want), null)
            Log.i(TAG, "switch $want: savedBlob=${savedBlob?.length ?: 0} chars")
            loadLocked(context, svc, savedBlob)
            p.edit().putString(ACTIVE, want).apply()
        } else {
            // Same owner as last time, but the engine can be empty for a moment while storage is
            // read (or after a crash between the import and the saved write). Fall back to the
            // saved copy so a sign-in is never silently lost at launch.
            val savedBlob = p.getString(blobKey(want), null)
            if (!savedBlob.isNullOrEmpty() && svc.exportAccounts().isEmpty()) {
                Log.i(TAG, "restore $want from its saved copy: ${savedBlob.length} chars")
                loadLocked(context, svc, savedBlob)
            }
        }
        engineHolder = want
        Log.i(TAG, "engine now holds $want: ${describe(context)}")
    }
}
