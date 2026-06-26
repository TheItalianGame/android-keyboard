# xloidflare

Personal Cloudflare Worker relay for encrypted Android-to-Windows clipboard sync.

Deployed URL:

```text
https://xloidflare.talesfromanother.workers.dev
```

The local relay token for clients is stored in `.relay-token`.

## Live multi-device model

Use one shared `pairId` for a trusted device group, for example one phone,
one desktop, and one laptop. Every connected client in that room receives
messages sent by the others. Use a different random `pairId` for a separate
group.

The relay is live-only. It does not store clipboard history and does not replay
missed events to offline devices.

Clients should generate and persist a stable `fromDevice` id, and should ignore
any incoming packet with a `fromDevice` matching their own id. Clients should
also remember recently applied `clipId` values so clipboard updates do not echo
back and forth between devices.

## Endpoints

- `GET /health`
- `GET /room/<pairId>` returns `{ "latest": null, "mode": "live-only" }`.
- `WebSocket /room/<pairId>` relays encrypted clipboard packets between online paired devices.
- `POST /room/<pairId>/share` uploads one file to R2 and relays the generated link as an encrypted `text/plain` clipboard packet.
- `POST /room/<pairId>/share/direct/start` starts a direct-to-R2 multipart upload for large files.
- `POST /room/<pairId>/share/direct/part` returns a short-lived presigned R2 `PUT` URL for one part.
- `POST /room/<pairId>/share/direct/complete` completes a direct multipart upload and relays the generated link.
- `POST /room/<pairId>/share/direct/abort` aborts an incomplete direct multipart upload.
- `GET /file/<pairId>/<id>/<name>` downloads an uploaded shared file until it expires.

All `/room/*` requests require:

```text
Authorization: Bearer <relay-token>
```

The Worker stores only the SHA-256 hash of that relay token as a Cloudflare secret named
`RELAY_TOKEN_SHA256`.

## Clip Packet

```json
{
  "kind": "clip",
  "clipId": "uuid",
  "fromDevice": "android",
  "mime": "text/plain",
  "ciphertext": "base64",
  "nonce": "base64",
  "createdAt": 1790000000000,
  "expiresAt": 1790000300000
}
```

Supported MIME types are `text/plain`, `image/png`, `image/jpeg`, `image/gif`,
and `image/webp`. Clipboard plaintext and image bytes must be encrypted
on-device before sending. The encrypted payload goes in `ciphertext`.

## File Sharing

The Android share target uploads one file at a time to R2 through:

```text
POST /room/<pairId>/share
Authorization: Bearer <relay-token>
Content-Type: <file MIME>
X-File-Name: <display filename>
X-Device-Id: <stable client id>
```

The Worker stores the file under `shares/<pairId>/...`, returns a public
download URL, and relays that URL to currently online paired devices as an
encrypted text clipboard packet. Links expire after 5 hours. A scheduled cleanup
removes expired files hourly, and download requests also delete expired objects
on access.

The R2 bucket binding is `FILES`, backed by bucket `xloidflare-shares`.

### Direct Multipart Uploads

Use direct multipart uploads for files larger than the Worker body limit. The
current max file size is 3 GiB. Recommended part size is 64 MiB.

Start:

```http
POST /room/<pairId>/share/direct/start
Authorization: Bearer <relay-token>
Content-Type: application/json

{"name":"video.mp4","mime":"video/mp4","bytes":3221225472}
```

Response:

```json
{
  "ok": true,
  "id": "file-id",
  "uploadId": "r2-multipart-upload-id",
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
POST /room/<pairId>/share/direct/part
Authorization: Bearer <relay-token>
Content-Type: application/json

{"id":"file-id","uploadId":"r2-multipart-upload-id","name":"video.mp4","partNumber":1}
```

Response contains a `PUT` URL valid for 15 minutes. Upload the raw bytes for
that part directly to the returned URL and capture the response `ETag`.

Complete:

```http
POST /room/<pairId>/share/direct/complete
Authorization: Bearer <relay-token>
Content-Type: application/json
X-Device-Id: windows-stable-id

{
  "id": "file-id",
  "uploadId": "r2-multipart-upload-id",
  "name": "video.mp4",
  "mime": "video/mp4",
  "bytes": 3221225472,
  "parts": [
    {"partNumber": 1, "etag": "\"etag-from-r2\""}
  ]
}
```

The response includes the 5-hour download URL and the Worker relays that URL as
an encrypted text clipboard packet.
