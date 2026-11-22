package com.nuvio.tv.youtube

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import com.liskovsoft.mediaserviceinterfaces.oauth.Account
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** One account or channel profile you can switch to. */
data class YtAccount(
    val id: Int,
    val name: String,
    val detail: String?,
    val avatar: String?,
    val selected: Boolean
)

data class YouTubeAccountState(
    val signedIn: Boolean = false,
    /** False until the first look at the account has finished, so "Sign in" isn't shown while it's still loading. */
    val checked: Boolean = false,
    /** The account or profile in use. */
    val accountName: String? = null,
    /** Every account and channel profile on this device. */
    val accounts: List<YtAccount> = emptyList(),
    /** The code to enter at [url] while a sign-in is waiting. */
    val code: String? = null,
    val url: String = "youtube.com/activate",
    val error: String? = null
) {
    /** What a phone should open to enter the code; the code is filled in when YouTube allows it. */
    val activateLink: String? get() = code?.let { "https://www.$url?user_code=${it.replace(" ", "-")}" }
}

/** Signs in to YouTube with the TV device-code flow, switches between accounts and profiles, and signs out. */
@HiltViewModel
class YouTubeAccountViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {
    private companion object {
        const val TAG = "YouTubeAccount"
        const val RECHECKS = 2
        const val RECHECK_DELAY_MS = 700L
    }

    private val _state = MutableStateFlow(YouTubeAccountState())
    val state: StateFlow<YouTubeAccountState> = _state.asStateFlow()

    private var signIn: Disposable? = null
    /** The list's ids are made from each account's key, since the engine's own account id is the same for every profile. */
    private var keyById: Map<Int, String> = emptyMap()

    /** OneTube and Music each keep their own complete sign-in: their own accounts, profiles and choice. */
    private var scope = AccountScope.ONETUBE

    /** Set by the screen that shows this account (Music uses its own choice). Call before the first [refresh]. */
    fun useScope(newScope: AccountScope) {
        scope = newScope
    }

    private val signInService get() = YouTubeAccountScopes.engine(context, scope).signInService

    private fun label(a: Account): String =
        a.name?.takeIf(String::isNotBlank) ?: a.email?.takeIf(String::isNotBlank) ?: "Account"

    fun refresh() = check(attempt = 0)

    /**
     * Right after the app starts, YouTube's sign-in data can still be loading and report "not signed in" for a
     * moment. So a "no" is looked at again before it is believed.
     */
    private fun check(attempt: Int) {
        // Reading accounts touches storage, so keep it off the main thread.
        Schedulers.io().scheduleDirect {
            val signed = runCatching { signInService.isSigned }.getOrDefault(false)
            if (!signed && attempt < RECHECKS) {
                Schedulers.io().scheduleDirect({ check(attempt + 1) }, RECHECK_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                return@scheduleDirect
            }
            val list = runCatching { YouTubeAccountScopes.accounts(context, scope) }.getOrDefault(emptyList())
            // Keep the saved copy current after a token refresh or a name/avatar sync.
            if (signed) runCatching { YouTubeAccountScopes.save(context, scope) }
            Log.i(TAG, "check $scope signed=$signed: ${YouTubeAccountScopes.describe(context)}")
            keyById = list.associateBy({ YouTubeAccountScopes.keyOf(it).hashCode() }, { YouTubeAccountScopes.keyOf(it) })
            val items = list.map {
                val key = YouTubeAccountScopes.keyOf(it)
                YtAccount(
                    id = key.hashCode(),
                    name = label(it),
                    // Only worth showing the email when the name is something else.
                    detail = it.email?.takeIf { e -> e.isNotBlank() && e != label(it) },
                    avatar = it.avatarImageUrl,
                    selected = it.isSelected
                )
            }
            val current = list.firstOrNull { it.isSelected }
            val currentName = current?.name?.takeIf(String::isNotBlank)
                ?: current?.email?.takeIf(String::isNotBlank)
            _state.update { it.copy(signedIn = signed, checked = true, accountName = currentName, accounts = items) }
        }
    }

    fun startSignIn() {
        signIn?.dispose()
        _state.update { it.copy(error = null, code = null) }
        signIn = signInService.signInObserve()
            .subscribeOn(Schedulers.io())
            .subscribe(
                { code -> _state.update { it.copy(code = code) } },
                { e ->
                    Log.e(TAG, "sign-in failed", e)
                    _state.update { it.copy(code = null, error = e.message ?: "Couldn't start sign-in") }
                },
                {
                    // Approved on the other device. The new account is in this part's own set; keep a saved copy of it.
                    _state.update { it.copy(code = null) }
                    runCatching { YouTubeAccountScopes.save(context, scope) }
                        .onFailure { Log.e(TAG, "couldn't save the new sign-in", it) }
                    refresh()
                }
            )
    }

    fun cancelSignIn() {
        signIn?.dispose()
        _state.update { it.copy(code = null) }
    }

    /**
     * A sign-in can finish before YouTube returns the account list (the name and avatar). Ask
     * once more for them, then refresh the account state.
     */
    fun refreshAccountInfo() {
        Schedulers.io().scheduleDirect {
            runCatching { signInService.refreshAccounts() }
            check(attempt = RECHECKS)
        }
    }

    /** Switches to another account or profile. [onDone] runs once it has taken effect. */
    fun selectAccount(id: Int, onDone: () -> Unit = {}) {
        Schedulers.io().scheduleDirect {
            runCatching { keyById[id]?.let { YouTubeAccountScopes.select(context, scope, it) } }
                .onFailure { Log.e(TAG, "couldn't switch account", it) }
            refresh()
            onDone()
        }
    }

    fun signOut(onDone: () -> Unit = {}) {
        Schedulers.io().scheduleDirect {
            // This part signs out of its current Google login; the other part keeps its own sign-in.
            runCatching { YouTubeAccountScopes.signOut(context, scope) }
                .onFailure { Log.e(TAG, "sign-out failed", it) }
            refresh()
            onDone()
        }
    }

    override fun onCleared() {
        signIn?.dispose()
    }
}
