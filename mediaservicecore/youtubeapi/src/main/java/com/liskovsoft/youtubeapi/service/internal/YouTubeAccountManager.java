package com.liskovsoft.youtubeapi.service.internal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.googlecommon.common.models.auth.AccessToken;
import com.liskovsoft.mediaserviceinterfaces.SignInService.OnAccountChange;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.misc.WeakHashSet;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.youtubeapi.app.AppService;
import com.liskovsoft.youtubeapi.auth.V2.AuthService;
import com.liskovsoft.googlecommon.common.models.auth.UserCode;
import com.liskovsoft.googlecommon.common.models.auth.info.AccountInt;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;
import com.liskovsoft.googlecommon.service.oauth.YouTubeAccount;

import io.reactivex.Observable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class YouTubeAccountManager {
    private static final String TAG = YouTubeAccountManager.class.getSimpleName();
    private static final int ACCOUNTS_LIST_ATTEMPTS = 2;
    private static final long ACCOUNTS_LIST_RETRY_DELAY_MS = 1_500;
    private static YouTubeAccountManager sInstance;
    private boolean mStorageSynced;
    private final YouTubeSignInService mSignInService;
    private final WeakHashSet<OnAccountChange> mListeners = new WeakHashSet<>();
    /**
     * Fix ConcurrentModificationException when using {@link #getSelectedAccount()}
     */
    private final List<Account> mAccounts = new CopyOnWriteArrayList<Account>() {
        @Override
        public boolean add(Account account) {
            if (account == null) {
                return false;
            }

            mergeAndRemove(account);

            return super.add(account);
        }

        private void mergeAndRemove(Account account) {
            int index = indexOf(account);

            if (index != -1) {
                Account matched = get(index);

                // Don't remove these lines or you won't be able to enter to the account.
                while (contains(account)) {
                    remove(account);
                }

                // Do merge after the remove not before!!!
                ((YouTubeAccount) account).merge(matched);
            }
        }
    };

    private YouTubeAccountManager(YouTubeSignInService signInService) {
        mSignInService = signInService;
    }

    public static YouTubeAccountManager instance(YouTubeSignInService signInService) {
        if (sInstance == null) {
            sInstance = new YouTubeAccountManager(signInService);
        }

        return sInstance;
    }

    public Observable<String> signInObserve() {
        return RxHelper.createLong(emitter -> {
            UserCode userCodeResult = getAuthService().getUserCode();

            if (userCodeResult == null) {
                RxHelper.onError(emitter, "User code result is empty");
                return;
            }

            emitter.onNext(userCodeResult.getUserCode());

            try {
                AccessToken token = getAuthService().getAccessTokenWait(
                        userCodeResult.getDeviceCode(),
                        userCodeResult.getInterval(),
                        emitter::isDisposed);

                // Cancelled while waiting.
                if (token == null || emitter.isDisposed()) {
                    return;
                }

                boolean saved = persistRefreshToken(token.getRefreshToken());

                if (emitter.isDisposed()) {
                    return;
                }

                if (!saved) {
                    RxHelper.onError(emitter, "Couldn't finish signing in. Try again.");
                    return;
                }

                emitter.onComplete();
            } catch (InterruptedException e) {
                // NOP
            }
        });
    }

    @Nullable
    public List<Account> getAccounts() {
        return mAccounts;
    }

    /**
     * Set selected account token.
     *
     * @return true when an account is selected afterwards
     */
    private boolean persistRefreshToken(String refreshToken) {
        if (refreshToken == null) {
            Log.e(TAG, "Refresh token is null");
            return false;
        }

        // Create initial account (with only refresh key)
        YouTubeAccount tempAccount = YouTubeAccount.fromToken(refreshToken);
        addAccount(tempAccount);

        // Use initial account to create auth header and fetch the accounts below
        mStorageSynced = true; // the account list is fetched below, with retries
        mSignInService.invalidateCache();
        mSignInService.checkAuth();

        // Remove initial account (with only refresh key)
        mAccounts.remove(tempAccount); // multi thread fix

        // The account list is a separate request and YouTube occasionally answers it with the
        // "automated queries" page right after a sign-in. Give it a few tries.
        List<AccountInt> accountsInt = null;

        for (int attempt = 0; attempt < ACCOUNTS_LIST_ATTEMPTS; attempt++) {
            try {
                accountsInt = getAuthService().getAccounts(); // runs under auth header from above
            } catch (Exception e) {
                Log.e(TAG, "Couldn't read the account list: %s", e.getMessage());
            }

            if (accountsInt != null && !accountsInt.isEmpty()) {
                break;
            }

            if (attempt < ACCOUNTS_LIST_ATTEMPTS - 1) {
                try {
                    Thread.sleep(ACCOUNTS_LIST_RETRY_DELAY_MS * (attempt + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (accountsInt != null && !accountsInt.isEmpty()) {
            for (AccountInt accountInt : accountsInt) {
                YouTubeAccount account = YouTubeAccount.from(accountInt);
                account.setRefreshToken(refreshToken);
                addAccount(account);
            }
        } else {
            // The sign-in itself worked even though the account list didn't come back. Keep the
            // token so the user is signed in; the name and avatar fill in on a later sync.
            Log.e(TAG, "Account list is empty; signing in with the token only");

            for (Account account : mAccounts) {
                if (account instanceof YouTubeAccount && ((YouTubeAccount) account).getRefreshToken() == null) {
                    mAccounts.remove(account);
                }
            }

            addAccount(YouTubeAccount.fromToken(refreshToken));
        }

        fixSelectedAccount();

        // Apply merged tokens
        mSignInService.checkAuth();

        persistAccounts();
        onAccountChanged();

        return getSelectedAccount() != null;
    }

    private void addAccount(Account newAccount) {
        if (newAccount.isSelected()) {
            for (Account account : mAccounts) {
                ((YouTubeAccount) account).setSelected(false);
            }
        }

        mAccounts.add(newAccount);
    }

    public void selectAccount(Account newAccount) {
        if (Helpers.equals(newAccount, getSelectedAccount())) {
            return;
        }

        for (Account account : mAccounts) {
            ((YouTubeAccount) account).setSelected(newAccount != null && newAccount.equals(account));
        }

        persistAccounts();

        onAccountChanged();
    }

    public void removeAccount(Account account) {
        if (account != null && mAccounts.contains(account)) {
            mAccounts.remove(account);
            persistAccounts();

            onAccountChanged();
        }
    }

    public Account getSelectedAccount() {
        for (Account account : mAccounts) {
            if (account != null && account.isSelected()) {
                return account;
            }
        }

        return null;
    }

    private void persistAccounts() {
        setAccountManagerData(Helpers.mergeArray(mAccounts.toArray()));
    }

    /**
     * The accounts as one string that {@link #importAccounts(String)} can load again. Lets the app keep several
     * separate sets of signed-in accounts and switch the engine between them.
     */
    public synchronized String exportAccounts() {
        return Helpers.mergeArray(mAccounts.toArray());
    }

    /** Replaces every account with the set in [data] (from {@link #exportAccounts()}; null or empty means none). */
    public synchronized void importAccounts(String data) {
        mAccounts.clear();

        if (data != null && !data.isEmpty()) {
            for (String spec : Helpers.splitArray(data)) {
                mAccounts.add(YouTubeAccount.from(spec));
            }
        }

        persistAccounts();
        onAccountChanged();
    }

    private void restoreAccounts() {
        String data = getAccountManagerData();

        if (data != null) {
            String[] split = Helpers.splitArray(data);
            mAccounts.clear();

            for (String spec : split) {
                mAccounts.add(YouTubeAccount.from(spec));
            }
        }

        //notifyListeners();
    }

    private void setAccountManagerData(String data) {
        // We don't have context, so can't create instance here.
        // Let's hope someone already created one for us.
        if (GlobalPreferences.sInstance == null) {
            Log.e(TAG, "GlobalPreferences is null!");
            return;
        }

        GlobalPreferences.sInstance.setMediaServiceAccountData(data);
    }

    private String getAccountManagerData() {
        // We don't have context, so can't create instance here.
        // Let's hope someone already created one for us.
        if (GlobalPreferences.sInstance == null) {
            Log.e(TAG, "GlobalPreferences is null!");
            return null;
        }

        return GlobalPreferences.sInstance.getMediaServiceAccountData();
    }

    public void init() {
        restoreAccounts();
    }

    public void addOnAccountChange(OnAccountChange listener) {
        if (!mListeners.contains(listener)) {
            if (listener instanceof MediaServicePrefs) {
                mListeners.add(0, listener);
            } else {
                mListeners.add(listener);
            }
        }
    }

    /**
     * Fix situations when there is no selected account<br/>
     * Mark first one as selected.
     */
    private void fixSelectedAccount() {
        if (mAccounts.isEmpty()) {
            return;
        }

        if (getSelectedAccount() == null) {
            selectAccount(mAccounts.get(0));
        }
    }

    private void onAccountChanged() {
        mSignInService.invalidateCache();
        AppService.instance().invalidateCache(); // regenerate visitor data
        FormatInfoWrapper.resetClient();

        notifyListeners();
    }

    /**
     * Sync avatars, names and emails
     */
    public void syncStorage() {
        syncStorage(false);
    }

    /**
     * @param force also tries when an earlier sync finished without updating anything
     */
    public void syncStorage(boolean force) {
        if (mStorageSynced && !force)
            return;

        List<Account> storedAccounts = getAccounts();

        if (storedAccounts == null || storedAccounts.isEmpty()) {
            mStorageSynced = true;
            return;
        }

        // A token-only account is kept when the account list wasn't available during sign-in.
        // Use its token so the first successful sync can fill in the name and avatar.
        String tokenOnly = null;

        if (storedAccounts.size() == 1) {
            Account only = storedAccounts.get(0);

            if (only instanceof YouTubeAccount && only.getName() == null && only.getEmail() == null) {
                tokenOnly = ((YouTubeAccount) only).getRefreshToken();
            }
        }

        List<AccountInt> newAccounts = null;

        try {
            newAccounts = getAuthService().getAccounts();
        } catch (Exception e) {
            Log.e(TAG, "Couldn't sync the account list: %s", e.getMessage());
        }

        Account selectedAccount = getSelectedAccount();

        if (newAccounts != null && !newAccounts.isEmpty()) {
            for (AccountInt newAccount : newAccounts) {
                YouTubeAccount account = YouTubeAccount.from(newAccount);

                if (account.getRefreshToken() == null && tokenOnly != null) {
                    account.setRefreshToken(tokenOnly);
                }

                account.setSelected(account.equals(selectedAccount));
                addAccount(account);
            }
            persistAccounts();
            mStorageSynced = true;
        }
        // On failure leave mStorageSynced false so a later check tries again.
    }

    private void notifyListeners() {
        Account account = getSelectedAccount();

        // Fix sign in bug
        mListeners.forEach(listener -> {
            if (listener instanceof MediaServicePrefs) {
                listener.onAccountChanged(account);
            } else {
                RxHelper.runUser(() -> listener.onAccountChanged(account));
            }
        });
    }

    @NonNull
    private static AuthService getAuthService() {
        return AuthService.instance();
    }
}
