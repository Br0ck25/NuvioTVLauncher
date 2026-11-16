# Spotify QR linking (Nuvio One TV)

The Music section can link a Spotify account without typing a password on the TV:

1. The TV creates a PKCE verifier and a random `state`, starts a small LAN callback server, and shows a QR code.
2. The phone opens the static HTTPS page in this folder, signs in to Spotify, and approves access.
3. Spotify redirects back to the static page, which forwards the one-time authorization code to the TV.
4. The TV exchanges the code itself and stores the refresh/access tokens in Android Keystore-backed storage.

No Spotify client secret is stored on the TV, and no token is sent to the phone page or put in the QR code.

## One-time setup

1. Create an app at <https://developer.spotify.com/dashboard>.
2. Add this **exact** redirect URI to the app:
   `https://<your-host>/<path>/callback.html`
   For example: `https://zoga2o3-ops.github.io/Nuvio-One/spotify-link/callback.html`.
3. Request the scopes:
   `playlist-read-private playlist-read-collaborative user-library-read user-read-private`
4. Host `index.html` and `callback.html` together over HTTPS (GitHub Pages, Cloudflare Pages, any static host). The callback page must be reachable at the redirect URI above.
5. Add these two lines to `local.properties` (or the environment) before building the TV app:
   ```
   SPOTIFY_CLIENT_ID=your_public_client_id
   SPOTIFY_LINK_BASE_URL=https://<your-host>/<path>
   ```
   `SPOTIFY_LINK_BASE_URL` is the folder that contains `index.html` and `callback.html`, without a trailing slash. The TV app builds the redirect URI as `${SPOTIFY_LINK_BASE_URL}/callback.html`.

## Testing the TV side without a Spotify app

With `SPOTIFY_CLIENT_ID` empty, the Music → Integrations → Spotify card says linking is not set up; the public playlist import still works. To see the QR panel, set a dummy client ID and a base URL; the QR will open the static page, but Spotify will reject the dummy client.

The callback server is only reachable on the same Wi-Fi/LAN as the TV. If the phone cannot reach the TV, the page shows a clear error; the TV keeps showing the QR until it expires (five minutes).
