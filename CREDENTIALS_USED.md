# Credentials Used

This is the safe, repo-trackable inventory. Raw tokens and secret keys are intentionally not included here.

The private file with raw values is:

- `/storage/emulated/0/Download/XLOIDFLARE_CREDENTIALS_USED_PRIVATE.md`
- `cloudflare/xloidflare/CREDENTIALS_USED_PRIVATE.md` local working-tree copy, ignored by git

## Non-secret Values

| Item | Value |
| --- | --- |
| GitHub account | `TheItalianGame` |
| Main fork | `https://github.com/TheItalianGame/android-keyboard` |
| Unused created repo | `https://github.com/TheItalianGame/android-keyboard-xloid` |
| Cloudflare account ID | `429d20e2971fc2ad19c63b4780c18b3b` |
| Worker name | `xloidflare` |
| Worker HTTP base | `https://xloidflare.talesfromanother.workers.dev` |
| Worker WebSocket base | `wss://xloidflare.talesfromanother.workers.dev/room` |
| Default pair ID | `personal_devices` |
| R2 bucket | `xloidflare-shares` |
| Android embedded room URL | `wss://xloidflare.talesfromanother.workers.dev/room` |
| Android embedded pair ID | `personal_devices` |

## Secret Categories

| Secret | Where it is used |
| --- | --- |
| GitHub CLI token | `gh` auth for creating/pushing repos |
| Cloudflare API token | Worker deploy through Cloudflare API |
| Relay token | Android `BuildConfig.XLOIDFLARE_RELAY_TOKEN`, Worker `RELAY_TOKEN`, and client auth/encryption |
| Relay token SHA-256 | Worker `RELAY_TOKEN_SHA256` |
| R2 S3 access key ID | Worker `R2_ACCESS_KEY_ID`, direct R2 upload signing |
| R2 S3 secret access key | Worker `R2_SECRET_ACCESS_KEY`, direct R2 upload signing |

## Source Locations

| File | Notes |
| --- | --- |
| `build.gradle` | Embeds default room URL, pair ID, and relay token from `cloudflare/xloidflare/.relay-token` |
| `cloudflare/xloidflare/.relay-token` | Local secret relay token, ignored by git |
| `cloudflare/xloidflare/.relay-token.sha256.tmp` | Local relay token hash, ignored by git |
| `cloudflare/xloidflare/wrangler.toml` | Worker name, account ID, Durable Object, R2 binding |
| `cloudflare/xloidflare/deploy-worker-direct.sh` | Prompts for Cloudflare API token and R2 S3 credentials |
| `cloudflare/xloidflare/src/index.js` | Uses Worker secrets and signs direct R2 upload URLs |
| `cloudflare/xloidflare/WINDOWS_TRAY_AGENT.md` | Documents client endpoint/auth behavior |

Do not commit the private credentials file. It is ignored by `.gitignore`.
