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
 * OneTube and Music keep fully separate YouTube sign-ins, even for the same Google account.
 *
 * The YouTube engine holds one set of accounts and cannot tell two sign-ins of the same Google account apart, so each
 * part gets its own complete set, kept here as a saved copy. Before a part uses the engine, [engine] makes the engine
 * hold that part's set: the set the engine was holding is saved first, then the part's set is loaded. A sign-in,
 * a profile choice or a sign-out in one part therefore never touches the other.
 *
 * The first time this runs, the accounts already signed in are copied to both parts (OneTube's set is the original),
 * so nobody is signed out by the update.
 */
object YouTubeAccountScopes {
    private const val TAG = "AccountScopes"
    private const val PREFS = "youtube_account_scopes_v3"
    private const val INITIALISED = "initialised"
    private const val ACTIVE = "active"

    private val lock = Any()

    /** Whose set of accounts the engine holds right now (null until this process has switched or confirmed it). */
    @Volatile
    private var engineScope: AccountScope? = null

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
        return "engineScope=$engineScope held=$held accounts=${list.size} [$rows]"
    }

    /** The engine, holding [scope]'s accounts. Blocking; call off the main thread when the set may change. */
    fun engine(context: Context, scope: AccountScope): ServiceManager {
        GlobalPreferences.instance(context.applicationContext)
        ensure(context, scope)
        return YouTubeServiceManager.instance()
    }

    /** Makes the engine hold [scope]'s accounts if it doesn't already. */
    fun ensure(context: Context, scope: AccountScope) {
        if (engineScope == scope) return
        synchronized(lock) {
            if (engineScope == scope) return
            switchLocked(context, scope)
        }
    }

    /** The accounts and profiles of [scope], with the one in use marked as selected. */
    fun accounts(context: Context, scope: AccountScope): List<Account> {
        GlobalPreferences.instance(context.applicationContext)
        ensure(context, scope)
        return runCatching { signIn()?.accounts.orEmpty().filterNotNull() }.getOrDefault(emptyList())
    }

    /** [scope] uses the profile with this [key]. */
    fun select(context: Context, scope: AccountScope, key: String) {
        synchronized(lock) {
            ensure(context, scope)
            val account = accounts(context, scope).firstOrNull { keyOf(it) == key } ?: return
            runCatching { signIn()?.selectAccount(account) }.onFailure { Log.w(TAG, "couldn't switch profile", it) }
            runCatching { YouTubeServiceManager.instance().invalidateCache() }
            saveLocked(context, scope)
            Log.i(TAG, "select $scope: ${describe(context)}")
        }
    }

    /** [scope] signed in (or its accounts changed in the engine): keep the saved copy up to date. */
    fun save(context: Context, scope: AccountScope) {
        synchronized(lock) {
            // A sign-in finishes on its own thread and OneTube may have used the engine in the
            // meantime. Make sure the engine really holds this scope before reading its export,
            // otherwise the new sign-in was silently dropped from the saved copy.
            ensure(context, scope)
            saveLocked(context, scope)
        }
    }

    /**
     * [scope] signs out of the Google login behind its selected profile, which takes that login's other profiles with
     * it. The other part keeps its own copy.
     */
    fun signOut(context: Context, scope: AccountScope) {
        synchronized(lock) {
            ensure(context, scope)
            val svc = signIn() ?: return
            val all = accounts(context, scope)
            val selected = all.firstOrNull { it.isSelected } ?: all.firstOrNull() ?: return
            val token = (selected as? YouTubeAccount)?.refreshToken
            all.filter { it === selected || (token != null && (it as? YouTubeAccount)?.refreshToken == token) }
                .forEach { runCatching { svc.removeAccount(it) }.onFailure { e -> Log.w(TAG, "couldn't remove account", e) } }
            selectFirstIfNoneLocked(svc)
            runCatching { YouTubeServiceManager.instance().invalidateCache() }
            // This empty export is intentional: the user asked for this scope to sign out.
            saveLocked(context, scope, allowEmpty = true)
        }
    }

    private fun blobKey(scope: AccountScope) = "blob_${scope.tag}"

    private fun saveLocked(context: Context, scope: AccountScope, allowEmpty: Boolean = false) {
        if (engineScope != scope) return
        val svc = signIn() ?: return
        val exported = svc.exportAccounts()
        if (exported.isBlank() && !allowEmpty) {
            // A failed token refresh makes the engine report no accounts for a moment. Writing
            // that empty list over the saved copy is what turned a temporary sign-in error into
            // a permanent sign-out for both OneTube and Music.
            Log.w(TAG, "not overwriting $scope's saved accounts with an empty export")
            return
        }
        // Refreshed tokens and account details arrive while a part is in use; keep its saved
        // copy current too, so the next launch doesn't restore an older token.
        if (prefs(context).getString(blobKey(scope), null) == exported) return
        prefs(context).edit().putString(blobKey(scope), exported).apply()
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
            ensure(context, scope)
            val svc = signIn() ?: return false

            fun authorized(): Boolean {
                svc.invalidateCache()
                runCatching { svc.checkAuth() }
                // printDebugInfo() reports header=ok only after an access token was obtained.
                val info = runCatching { svc.printDebugInfo() }.getOrDefault("")
                return info.contains("header=ok")
            }

            if (authorized()) {
                saveLocked(context, scope)
                Log.i(TAG, "recover $scope: token refreshed")
                return true
            }

            val savedBlob = prefs(context).getString(blobKey(scope), null)
            if (savedBlob.isNullOrEmpty()) return false
            Log.i(TAG, "recover $scope: restoring saved accounts")
            svc.importAccounts(savedBlob)
            selectFirstIfNoneLocked(svc)
            if (authorized()) {
                saveLocked(context, scope)
                Log.i(TAG, "recover $scope: restored and refreshed")
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

    private fun switchLocked(context: Context, scope: AccountScope) {
        GlobalPreferences.instance(context.applicationContext)
        val svc = signIn() ?: return
        val p = prefs(context)
        // First run: the accounts already signed in are the original set, so both parts start with a copy of it.
        if (!p.getBoolean(INITIALISED, false)) {
            val current = svc.exportAccounts().ifEmpty { GlobalPreferences.instance(context.applicationContext).mediaServiceAccountData.orEmpty() }
            p.edit()
                .putString(blobKey(AccountScope.ONETUBE), current)
                .putString(blobKey(AccountScope.MUSIC), current)
                .putString(ACTIVE, AccountScope.ONETUBE.tag)
                .putBoolean(INITIALISED, true)
                .apply()
        }
        val holder = AccountScope.entries.firstOrNull { it.tag == p.getString(ACTIVE, null) } ?: AccountScope.ONETUBE
        if (holder != scope) {
            // Keep what the engine holds (a sign-in, a profile choice, refreshed names) under its owner, then load ours.
            // Early in a launch the engine may not have read its saved accounts yet; never overwrite a copy with that.
            svc.exportAccounts().takeIf { it.isNotEmpty() }?.let { p.edit().putString(blobKey(holder), it).apply() }
            val savedBlob = p.getString(blobKey(scope), null)
            Log.i(TAG, "switch $scope: savedBlob=${savedBlob?.length ?: 0} chars")
            svc.importAccounts(savedBlob)
            selectFirstIfNoneLocked(svc)
            runCatching { YouTubeServiceManager.instance().invalidateCache() }
            p.edit().putString(ACTIVE, scope.tag).apply()
        } else {
            // Same owner as last time, but the engine can be empty for a moment while storage is
            // read (or after a crash between the import and the saved write). Fall back to the
            // saved copy so a sign-in is never silently lost at launch.
            val savedBlob = p.getString(blobKey(scope), null)
            if (!savedBlob.isNullOrEmpty() && svc.exportAccounts().isEmpty()) {
                Log.i(TAG, "restore $scope from its saved copy: ${savedBlob.length} chars")
                svc.importAccounts(savedBlob)
                selectFirstIfNoneLocked(svc)
                runCatching { YouTubeServiceManager.instance().invalidateCache() }
            }
        }
        engineScope = scope
        Log.i(TAG, "engine now holds $scope: ${describe(context)}")
    }
}
