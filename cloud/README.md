# cloud: setup-sync service

A small Cloudflare Worker (with a D1 database) that keeps one copy of a person's Live TV setup per Nuvio
account, so a new TV that signs in to Nuvio gets the same setup. The app (`LiveTvCloudSync`) talks to it.

Based on `homelessbrian/livetv-cloud`, used with its author's permission. This copy keeps only the
setup-sync routes (no app key door, sports files, ESPN timer or private test builds).

How it works: the app sends the Nuvio access token it already has; the Worker asks `api.nuvio.tv` whose token
it is (cached 5 minutes) and never stores or logs it. The saved setup (it holds IPTV logins and the parental
PIN) is squeezed and encrypted with AES-256-GCM, with a separate key per account made from the secret
`SYNC_ENCRYPTION_KEY`. A save carries the revision it was based on; a stale save is refused with 409.

| Method | Path | Does |
|---|---|---|
| GET | `/v1/health` | Is the service up; says whether the encryption secret is set |
| GET | `/v1/setup` | The saved copy, or 404 |
| PUT | `/v1/setup` | Save `{baseRevision, device, deviceId, data}`; 409 if out of date |
| DELETE | `/v1/setup` | Delete the saved copy |
| GET | `/v1/setup/previous` | The copy from before the last save, or 404 |

Limits: 5 MB per save, about 1.9 MB stored per copy, 500 saves per account per day.

## Deploy (Cloudflare account)

```
npm install
npx wrangler login
npx wrangler d1 create nuviotv-sync          # put the printed database_id in wrangler.toml
npx wrangler secret put SYNC_ENCRYPTION_KEY  # 32+ random characters; keep a copy: without it saved setups cannot be read
npx wrangler deploy
```

The tables are made on first use. Check it: `GET /v1/health` should say `"encryption":"ready"`.
If you deploy under another name or account, change `CLOUD_URL` in `LiveTvCloudSync.kt` to match.

## Tests

`npm install && npm test` (runs the Worker in Miniflare against an emulated D1 and a pretend Nuvio sign-in).
