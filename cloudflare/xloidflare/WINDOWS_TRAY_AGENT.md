# Xloidflare Windows Tray Agent Spec

Build a tiny Windows tray app that syncs clipboard text and images with the Android keyboard through the deployed Cloudflare Worker.

## Branding / Icons

Use these icon assets from the Android repo or Downloads:

- SVG: `xloidflare.svg`
- PNG: `xloidflare.png`
- ICO: `xloidflare.ico`

Repo copies:

```text
cloudflare/xloidflare/assets/exported/xloidflare.svg
cloudflare/xloidflare/assets/exported/xloidflare-256.png
cloudflare/xloidflare/assets/exported/xloidflare.ico
```

Use `xloidflare.ico` for the Windows tray/app icon.

## Relay

- Base room URL: `wss://xloidflare.talesfromanother.workers.dev/room`
- Room path: `<base>/<pairId>`
- Auth header: `Authorization: Bearer <relay-token>`
- Local Android defaults:
  - Pair ID: `personal_devices`
  - Relay token: read from Android repo file `cloudflare/xloidflare/.relay-token`

The relay is live-only. It does not store history and does not replay missed clipboard events. If the Windows app is offline or disconnected, it misses that event.

## Protocol

Open one WebSocket to:

```text
wss://xloidflare.talesfromanother.workers.dev/room/<pairId>
```

Send and receive JSON:

```json
{
  "kind": "clip",
  "clipId": "uuid",
  "fromDevice": "windows-<stable-guid>",
  "source": "clipboard",
  "mime": "text/plain",
  "ciphertext": "base64",
  "nonce": "base64",
  "createdAt": 1790000000000,
  "expiresAt": 1790000060000
}
```

Supported MIME types:

- `text/plain`
- `image/png`
- `image/jpeg`
- `image/gif`
- `image/webp`

Supported `source` values:

- `clipboard`: user/local clipboard capture.
- `screenshot`: user/local screenshot capture.
- `file-share`: URL generated after a file upload.

Ignore packets where `fromDevice` equals this Windows client’s stable device ID. Also remember recently applied `clipId` and content hashes so clipboard changes applied from the relay do not echo back.

Important source and echo-loop rules:

- Every outbound packet must use this client's stable `fromDevice`.
- Every outbound packet must set `source` to one of the supported values.
- Only send clipboard changes that came from the OS/user, not changes the agent just applied from the Worker.
- Keep a recent digest cache for at least 2 minutes.
- Digest should be `SHA-256(mime + NUL + rawBytes)`.
- Keep a recent `clipId` cache for at least 2 minutes.
- Before sending any local clipboard update, skip it if its digest is in the recent digest cache.
- Before sending any local clipboard update, skip it if the clipboard was last written by this agent from a remote packet and has not changed since that write.
- When sending a local clipboard update, add its digest to the recent digest cache.
- When applying a remote clipboard update, add both the remote `clipId` and the content digest to the recent caches before calling Windows `Clipboard.SetText()` or setting an image.
- Set a short `suppressClipboardUntil` timestamp around any agent-initiated clipboard write, and ignore clipboard-change events while that flag is active.
- After setting remote clipboard data, store `lastAgentWriteDigest = digest` and `lastAgentWriteClipId = clipId`; if the next clipboard read has that digest, do not send it even after the short suppress window.
- If the user later copies the same content manually from another app, Windows may expose the same digest. That is acceptable to suppress; the alternative causes relay loops.
- This is required for text, screenshots, images, and file-share URLs, otherwise Android -> Windows -> Android can loop.

## Encryption

Payloads are encrypted on-device before sending. The Worker only relays encrypted bytes.

Use AES-256-GCM:

- Nonce: 12 random bytes
- Tag: 128 bits
- `ciphertext`: base64 of AES-GCM output including auth tag
- `nonce`: base64 nonce
- Key derivation:

```text
SHA-256("xloidflare:" + pairId + ":" + relayToken)
```

Encrypt raw UTF-8 bytes for `text/plain`.

For images, encrypt the raw encoded image bytes, not decoded pixels. Prefer PNG for screenshots or clipboard bitmaps if Windows gives a DIB/bitmap. Keep image payloads under about 8 MiB before encryption/base64.

## Tray App Behavior

Required:

- Run in the Windows notification area.
- Start automatically on login if enabled.
- Maintain a persistent WebSocket connection and reconnect with backoff.
- Watch local clipboard changes.
- Send local text and image clipboard changes while connected.
- Apply remote text and images to the Windows clipboard.
- Avoid echo loops.
- Show simple tray states:
  - Connected
  - Reconnecting
  - Disabled
  - Error

Tray menu:

- Enable sync checkbox
- Connect now
- Pair ID
- Relay token
- Start on login checkbox
- Quit

Store settings locally in a user config file or Windows Credential Manager. For a personal first cut, a JSON config under `%APPDATA%\Xloidflare\config.json` is acceptable, but do not log the relay token.

## Suggested Stack

Good small options:

- C#/.NET 8 WinForms tray app
- `ClientWebSocket` for WebSocket
- `System.Security.Cryptography.AesGcm`
- Windows Clipboard via WinForms `Clipboard`
- Single-file publish if desired

Keep dependencies minimal.

## Clipboard Details

Text:

- On local `Clipboard.ContainsText()`, read `Clipboard.GetText()`, encrypt UTF-8, send `mime = "text/plain"`.
- On local text send, set `source = "clipboard"`.
- On remote text, decrypt and call `Clipboard.SetText()` under the suppress/write-marker rules above.

Images:

- On local image, get `Clipboard.GetImage()`, encode as PNG to memory, encrypt bytes, send `mime = "image/png"`.
- On local image send, set `source = "clipboard"`.
- On remote image, decrypt bytes and set clipboard image under the suppress/write-marker rules above.
- If preserving original encoded formats is easy, support PNG/JPEG/WebP/GIF. If not, PNG-only from Windows is acceptable for first version.

Files:

- No special receive path is required for shared-file links.
- Android or Windows uploads a shared file to R2 and the Worker relays the generated download URL as normal encrypted `text/plain`.
- Windows should apply that URL to the clipboard like any other text clip.
- Links expire after 5 hours.

### Small File Upload

For files up to about 95 MiB, Windows may use the simple Worker upload endpoint:

```http
POST https://xloidflare.talesfromanother.workers.dev/room/<pairId>/share
Authorization: Bearer <relay-token>
Content-Type: <file MIME>
X-File-Name: <filename>
X-Device-Id: windows-<stable-guid>

<raw file bytes>
```

Response:

```json
{
  "ok": true,
  "url": "https://xloidflare.talesfromanother.workers.dev/file/personal_devices/<file-id>/<filename>",
  "expiresAt": 1782254014659,
  "bytes": 123456,
  "name": "file.png",
  "mime": "image/png"
}
```

On success:

- Copy `url` to the Windows clipboard.
- Add digest for `text/plain + NUL + url UTF-8 bytes` to the recent digest cache before setting clipboard.
- Mark the clipboard write as agent-originated, same as a remote packet, because the Worker already relays this URL to online paired devices.
- The Worker also sends this URL to online paired devices.

### Large Direct R2 Upload

For larger files up to 3 GiB, use direct multipart upload. This avoids pushing file bytes through the Worker.

Limits:

- Max file size: `3221225472` bytes, exactly 3 GiB.
- Recommended part size: `67108864` bytes, 64 MiB.
- Every part except the last must be at least 5 MiB.
- Presigned R2 `PUT` URLs expire after 15 minutes.
- Final download link expires after 5 hours.

Start:

```http
POST https://xloidflare.talesfromanother.workers.dev/room/<pairId>/share/direct/start
Authorization: Bearer <relay-token>
Content-Type: application/json

{
  "name": "video.mp4",
  "mime": "video/mp4",
  "bytes": 123456789
}
```

Start response:

```json
{
  "ok": true,
  "id": "<file-id>",
  "uploadId": "<r2-multipart-upload-id>",
  "name": "video.mp4",
  "mime": "video/mp4",
  "expiresAt": 1782256731783,
  "partSize": 67108864,
  "minPartSize": 5242880,
  "maxPartSize": 99614720,
  "maxBytes": 3221225472
}
```

For each part:

```http
POST https://xloidflare.talesfromanother.workers.dev/room/<pairId>/share/direct/part
Authorization: Bearer <relay-token>
Content-Type: application/json

{
  "id": "<file-id>",
  "uploadId": "<r2-multipart-upload-id>",
  "name": "video.mp4",
  "partNumber": 1
}
```

Part response:

```json
{
  "ok": true,
  "method": "PUT",
  "url": "https://xloidflare-shares.<account-id>.r2.cloudflarestorage.com/...",
  "partNumber": 1,
  "expiresIn": 900,
  "headers": {
    "x-amz-content-sha256": "UNSIGNED-PAYLOAD"
  }
}
```

Upload the raw part bytes directly to `url`. Apply every header from the `headers` object exactly as returned. Do not add `Authorization`, do not add other `x-amz-*` headers, and keep the URL unchanged.

```http
PUT <url from part response>
x-amz-content-sha256: UNSIGNED-PAYLOAD

<raw part bytes>
```

Save the response `ETag` header exactly as returned.

Complete:

```http
POST https://xloidflare.talesfromanother.workers.dev/room/<pairId>/share/direct/complete
Authorization: Bearer <relay-token>
Content-Type: application/json
X-Device-Id: windows-<stable-guid>

{
  "id": "<file-id>",
  "uploadId": "<r2-multipart-upload-id>",
  "name": "video.mp4",
  "mime": "video/mp4",
  "bytes": 123456789,
  "parts": [
    { "partNumber": 1, "etag": "\"etag-from-r2\"" },
    { "partNumber": 2, "etag": "\"etag-from-r2\"" }
  ]
}
```

Complete response:

```json
{
  "ok": true,
  "url": "https://xloidflare.talesfromanother.workers.dev/file/personal_devices/<file-id>/video.mp4",
  "expiresAt": 1782256731783,
  "bytes": 123456789,
  "name": "video.mp4",
  "mime": "video/mp4",
  "parts": 2
}
```

On success:

- Copy `url` to the Windows clipboard.
- Add digest for the URL text to the recent digest cache before setting clipboard.
- Mark the clipboard write as agent-originated, same as a remote packet, because the Worker already relays this URL to online paired devices.
- The Worker also relays the URL to online paired devices as encrypted `text/plain`.

Abort on cancel/failure:

```http
POST https://xloidflare.talesfromanother.workers.dev/room/<pairId>/share/direct/abort
Authorization: Bearer <relay-token>
Content-Type: application/json

{
  "id": "<file-id>",
  "uploadId": "<r2-multipart-upload-id>",
  "name": "video.mp4"
}
```

### Windows UX for Sending Files

Add tray menu commands:

- `Send file...`
- `Send folder as zip...` optional

Behavior:

- Pick a file with standard Windows file picker.
- If `size <= 95 MiB`, use small upload.
- If `95 MiB < size <= 3 GiB`, use direct multipart.
- Show progress: bytes uploaded, current part, upload speed, cancel button.
- On success, set clipboard to the returned URL and show a tray balloon/toast.
- On cancel or error after multipart start, call abort.

## Backoff

Reconnect after disconnect:

- 1s, 2s, 5s, then cap at 15s.
- Reset to 1s after a successful connection.

## Limits

- Text plaintext max: 96 KiB
- Image plaintext max: 8 MiB
- Packet expiration: `createdAt + 60000`

Drop anything expired or too large.
