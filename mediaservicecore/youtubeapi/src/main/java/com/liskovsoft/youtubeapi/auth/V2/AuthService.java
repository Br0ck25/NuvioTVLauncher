package com.liskovsoft.youtubeapi.auth.V2;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.youtubeapi.app.AppService;
import com.liskovsoft.googlecommon.common.models.auth.AccessToken;
import com.liskovsoft.googlecommon.common.models.auth.UserCode;
import com.liskovsoft.googlecommon.common.models.auth.info.AccountInt;
import com.liskovsoft.googlecommon.common.models.auth.info.AccountsList;
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper;
import retrofit2.Call;

import java.util.List;

public class AuthService {
    private static final String TAG = AuthService.class.getSimpleName();
    private static AuthService sInstance;
    private final AuthApi mAuthApi;
    private static final int REFRESH_TOKEN_ATTEMPTS = 200;
    private static final long REFRESH_TOKEN_ATTEMPT_INTERVAL_MS = 5_000;
    /** Added to the interval when YouTube asks us to slow down (or keeps failing). */
    private static final long SLOW_DOWN_EXTRA_MS = 5_000;
    private static final long MAX_INTERVAL_MS = 15_000;
    private final AppService mAppService;

    private AuthService() {
        mAuthApi = RetrofitHelper.create(AuthApi.class);
        mAppService = AppService.instance();
    }

    public static AuthService instance() {
        if (sInstance == null) {
            sInstance = new AuthService();
        }

        return sInstance;
    }

    /**
     * Returns user code that user should apply on the page<br/>
     * <a href=https://youtube.com/activate>https://youtube.com/activate</a>
     * @return response with user code and device code
     */
    public UserCode getUserCode() {
        Call<UserCode> wrapper = mAuthApi.getUserCode(
                AuthApiHelper.getUserCodeQuery(mAppService.getClientId())
        );
        return RetrofitHelper.get(wrapper);
    }

    /**
     * Note, before calling this method user should apply the 'user code' on the page<br/>
     * <a href=https://youtube.com/activate>https://youtube.com/activate</a>
     * @param deviceCode the code contained inside the response of the method {@link #getUserCode()}
     * @return refresh token that should be stored inside the app registry for future use
     */
    public AccessToken getAccessToken(String deviceCode) {
        Call<AccessToken> wrapper = mAuthApi.getAccessToken(
                AuthApiHelper.getRefreshTokenQuery(
                        deviceCode,
                        mAppService.getClientId(),
                        mAppService.getClientSecret())
        );
        return RetrofitHelper.get(wrapper);
    }

    /**
     * Returns temporal access token that should be refreshed after some period of time
     * @param refreshToken token obtained from previous method
     * @return temporal access token
     */
    public AccessToken updateAccessToken(String refreshToken) {
        Call<AccessToken> wrapper = mAuthApi.updateAccessToken(
                AuthApiHelper.getAccessTokenQuery(refreshToken,
                        mAppService.getClientId(),
                        mAppService.getClientSecret())
        );
        return RetrofitHelper.getWithErrors(wrapper);
    }

    public AccessToken updateAccessTokenRaw(String rawJsonAuthData) {
        Call<AccessToken> wrapper = mAuthApi.updateAccessToken(rawJsonAuthData);
        return RetrofitHelper.get(wrapper);
    }

    public AccessToken getAccessTokenWait(String deviceCode) throws InterruptedException {
        return getAccessTokenWait(deviceCode, 0, null);
    }

    /**
     * Polls until the code is approved. Uses the interval YouTube returned with the code (never
     * faster than 5 s), backs off when YouTube says {@code slow_down} or keeps failing, and stops
     * as soon as the caller is no longer interested (Cancel).
     *
     * @return the token, or {@code null} when the caller cancelled
     */
    public AccessToken getAccessTokenWait(String deviceCode, int intervalSeconds,
                                          java.util.function.BooleanSupplier isCancelled) throws InterruptedException {
        AccessToken tokenResult = null;
        long intervalMs = Math.max(intervalSeconds, 5) * 1_000L;
        int consecutiveFailures = 0;

        for (int i = 0; i < REFRESH_TOKEN_ATTEMPTS; i++) {
            if (isCancelled != null && isCancelled.getAsBoolean()) {
                return null;
            }

            Thread.sleep(intervalMs);

            if (isCancelled != null && isCancelled.getAsBoolean()) {
                return null;
            }

            tokenResult = getAccessToken(deviceCode);

            if (tokenResult != null && tokenResult.getRefreshToken() != null) {
                return tokenResult;
            }

            String error = tokenResult != null ? tokenResult.getError() : null;

            if ("slow_down".equals(error)) {
                intervalMs = Math.min(intervalMs + SLOW_DOWN_EXTRA_MS, MAX_INTERVAL_MS);
            } else if ("access_denied".equals(error) || "expired_token".equals(error)) {
                break;
            } else if (tokenResult == null) {
                // Network problem or YouTube's "automated queries" page. Ease off instead of
                // hammering the endpoint, which only makes the block last longer.
                consecutiveFailures++;
                if (consecutiveFailures >= 3) {
                    intervalMs = Math.min(intervalMs + SLOW_DOWN_EXTRA_MS, MAX_INTERVAL_MS);
                    consecutiveFailures = 0;
                }
            } else {
                consecutiveFailures = 0;
            }
        }

        if (tokenResult != null && tokenResult.getRefreshToken() != null) {
            return tokenResult;
        } else {
            String debug = String.format("Error. Refresh token is empty!\nDebug data: device code: %s, client id: %s, client secret: %s\nError msg: %s",
                    deviceCode,
                    mAppService.getClientId(),
                    mAppService.getClientSecret(),
                    tokenResult != null ? tokenResult.getError() : "");

            Log.e(TAG, debug);
            throw new IllegalStateException("YouTube didn't finish signing in. Please try again.");
        }
    }

    /**
     * NOTE: Requires the auth header to be set before the call
     */
    @Nullable
    public List<AccountInt> getAccounts() {
        Call<AccountsList> wrapper = mAuthApi.getAccountsList(AuthApiHelper.getAccountsListQuery());

        AccountsList accountsList = RetrofitHelper.get(wrapper);

        return accountsList != null ? accountsList.getAccounts() : null;
    }
}
