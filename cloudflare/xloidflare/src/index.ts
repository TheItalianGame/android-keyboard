export interface Env {
  ROOMS: DurableObjectNamespace<ClipboardRoom>;
  FILES?: R2Bucket;
  RELAY_TOKEN_SHA256?: string;
  RELAY_TOKEN?: string;
  DEBUG_LOG_TEXT?: string;
}

type ClipMime = "text/plain" | "image/png" | "image/jpeg" | "image/gif" | "image/webp";

type ClipPacket = {
  kind: "clip";
  clipId: string;
  fromDevice: string;
  mime: ClipMime;
  ciphertext: string;
  nonce: string;
  createdAt: number;
  expiresAt: number;
};

type ClientMessage =
  | ClipPacket
  | { kind: "ack"; clipId: string; fromDevice: string }
  | { kind: "ping"; at?: number };

const MAX_PAIR_ID_LENGTH = 96;
const MAX_TEXT_CIPHERTEXT_LENGTH = 128 * 1024;
const MAX_IMAGE_CIPHERTEXT_LENGTH = 11 * 1024 * 1024;
const MAX_MESSAGE_LENGTH = MAX_IMAGE_CIPHERTEXT_LENGTH + 4096;
const MAX_CLIP_TTL_MS = 5 * 60 * 1000;
const SHARE_LINK_TTL_MS = 5 * 60 * 60 * 1000;
const MAX_SHARED_FILE_BYTES = 3 * 1024 * 1024 * 1024;
const MAX_SINGLE_WORKER_UPLOAD_BYTES = 95 * 1024 * 1024;
const MULTIPART_CHUNK_BYTES = 64 * 1024 * 1024;
const MIN_MULTIPART_PART_BYTES = 5 * 1024 * 1024;
const TEXT_MIME = "text/plain";
const IMAGE_MIMES = new Set(["image/png", "image/jpeg", "image/gif", "image/webp"]);

function textResponse(text: string, status = 200): Response {
  return new Response(text, {
    status,
    headers: { "content-type": "text/plain; charset=utf-8" }
  });
}

function jsonResponse(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" }
  });
}

function hex(bytes: ArrayBuffer): string {
  return [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function sha256(value: string): Promise<string> {
  return hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)));
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

async function isAuthorized(request: Request, env: Env): Promise<boolean> {
  if (!env.RELAY_TOKEN_SHA256) return false;

  const header = request.headers.get("authorization") ?? "";
  const token = header.startsWith("Bearer ") ? header.slice("Bearer ".length) : "";
  if (!token) return false;

  return timingSafeEqual(await sha256(token), env.RELAY_TOKEN_SHA256);
}

function pairIdFromPath(pathname: string): string | null {
  const parts = pathname.split("/").filter(Boolean);
  if (parts.length !== 2 || parts[0] !== "room") return null;
  return validatePairId(parts[1]);
}

function pairIdFromSharePath(pathname: string): string | null {
  const parts = pathname.split("/").filter(Boolean);
  if (parts.length !== 3 || parts[0] !== "room" || parts[2] !== "share") return null;
  return validatePairId(parts[1]);
}

function pairIdFromShareCommandPath(pathname: string): { pairId: string; command: string } | null {
  const parts = pathname.split("/").filter(Boolean);
  if (parts.length !== 4 || parts[0] !== "room" || parts[2] !== "share") return null;
  const pairId = validatePairId(parts[1]);
  if (!pairId) return null;
  if (!["start", "part", "complete", "abort"].includes(parts[3])) return null;
  return { pairId, command: parts[3] };
}

function validatePairId(rawPairId: string): string | null {
  const pairId = decodeURIComponent(rawPairId);
  if (!/^[A-Za-z0-9_-]+$/.test(pairId)) return null;
  if (pairId.length < 12 || pairId.length > MAX_PAIR_ID_LENGTH) return null;
  return pairId;
}

function fileFromPath(pathname: string): { pairId: string; id: string; name: string } | null {
  const parts = pathname.split("/").filter(Boolean);
  if (parts.length < 4 || parts[0] !== "file") return null;
  const pairId = validatePairId(parts[1]);
  const id = parts[2];
  if (!pairId || !/^[A-Za-z0-9_-]+$/.test(id)) return null;
  return { pairId, id, name: sanitizeFileName(decodeURIComponent(parts.slice(3).join("/"))) };
}

function sharedFileKey(pairId: string, id: string, name: string): string {
  return `shares/${pairId}/${id}/${name}`;
}

function sanitizeFileName(name: string | null | undefined): string {
  const fallback = "shared-file";
  const cleaned = (name ?? fallback)
    .replace(/[\\/:*?"<>|\u0000-\u001F]/g, "_")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 120);
  return cleaned || fallback;
}

function contentDisposition(name: string): string {
  return `attachment; filename="${name.replace(/["\\]/g, "_")}"; filename*=UTF-8''${encodeURIComponent(name)}`;
}

function shareLink(baseUrl: string, pairId: string, id: string, fileName: string): string {
  return `${baseUrl}/file/${encodeURIComponent(pairId)}/${id}/${encodeURIComponent(fileName)}`;
}

function isAllowedMime(mime: string): boolean {
  return mime === TEXT_MIME || IMAGE_MIMES.has(mime);
}

function base64ToBytes(value: string): Uint8Array {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

async function deriveAesKey(pairId: string, relayToken: string): Promise<CryptoKey> {
  const material = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(`xloidflare:${pairId}:${relayToken}`)
  );
  return crypto.subtle.importKey("raw", material, { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
}

async function decryptTextPacket(pairId: string, relayToken: string, packet: ClipPacket): Promise<string> {
  const key = await deriveAesKey(pairId, relayToken);
  const plaintext = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: base64ToBytes(packet.nonce), tagLength: 128 },
    key,
    base64ToBytes(packet.ciphertext)
  );
  return new TextDecoder().decode(plaintext);
}

async function buildEncryptedTextPacket(
  pairId: string,
  relayToken: string,
  fromDevice: string,
  text: string,
  expiresAt: number
): Promise<ClipPacket> {
  const key = await deriveAesKey(pairId, relayToken);
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: nonce, tagLength: 128 },
    key,
    new TextEncoder().encode(text)
  );
  return {
    kind: "clip",
    clipId: crypto.randomUUID(),
    fromDevice,
    mime: TEXT_MIME,
    ciphertext: bytesToBase64(new Uint8Array(ciphertext)),
    nonce: bytesToBase64(nonce),
    createdAt: Date.now(),
    expiresAt
  };
}

async function handleFileDownload(env: Env, file: { pairId: string; id: string; name: string }): Promise<Response> {
  if (!env.FILES) return textResponse("File storage is not configured", 503);

  const object = await env.FILES.get(sharedFileKey(file.pairId, file.id, file.name));
  if (!object) return textResponse("Not found", 404);

  const expiresAt = Number(object.customMetadata?.expiresAt ?? 0);
  if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
    await env.FILES.delete(sharedFileKey(file.pairId, file.id, file.name));
    return textResponse("Expired", 410);
  }

  return new Response(object.body, {
    headers: {
      "content-type": object.httpMetadata?.contentType ?? "application/octet-stream",
      "content-length": String(object.size),
      "content-disposition": contentDisposition(file.name),
      "cache-control": "private, max-age=60",
      "x-expires-at": String(expiresAt)
    }
  });
}

async function cleanupExpiredFiles(env: Env): Promise<void> {
  if (!env.FILES) return;

  let cursor: string | undefined;
  const now = Date.now();
  do {
    const listed = await env.FILES.list({
      prefix: "shares/",
      cursor,
      limit: 1000,
      include: ["customMetadata"]
    });
    await Promise.all(listed.objects.map(async (object) => {
      const expiresAt = Number(object.customMetadata?.expiresAt ?? 0);
      if (!Number.isFinite(expiresAt) || expiresAt <= now) {
        await env.FILES?.delete(object.key);
      }
    }));
    cursor = listed.truncated ? listed.cursor : undefined;
  } while (cursor);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return jsonResponse({ ok: true, service: "xloidflare", files: Boolean(env.FILES) });
    }

    const file = fileFromPath(url.pathname);
    if (file && request.method === "GET") {
      return handleFileDownload(env, file);
    }

    if (!(await isAuthorized(request, env))) {
      return textResponse("Unauthorized", 401);
    }

    const sharePairId = pairIdFromSharePath(url.pathname);
    if (sharePairId) {
      const id = env.ROOMS.idFromName(sharePairId);
      return env.ROOMS.get(id).fetch(request);
    }

    const shareCommand = pairIdFromShareCommandPath(url.pathname);
    if (shareCommand) {
      const id = env.ROOMS.idFromName(shareCommand.pairId);
      return env.ROOMS.get(id).fetch(request);
    }

    const pairId = pairIdFromPath(url.pathname);
    if (!pairId) {
      return textResponse("Use /room/<pairId>", 404);
    }

    const id = env.ROOMS.idFromName(pairId);
    return env.ROOMS.get(id).fetch(request);
  },

  async scheduled(_controller: ScheduledController, env: Env): Promise<void> {
    await cleanupExpiredFiles(env);
  }
};

export class ClipboardRoom {
  private sessions = new Set<WebSocket>();
  private pairId?: string;

  constructor(
    private readonly state: DurableObjectState,
    private readonly env: Env
  ) {
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const shareCommand = pairIdFromShareCommandPath(url.pathname);
    const pairId = pairIdFromPath(url.pathname) ?? pairIdFromSharePath(url.pathname) ?? shareCommand?.pairId;
    if (!pairId) return textResponse("Use /room/<pairId>", 404);
    this.pairId = pairId;

    if (request.method === "POST" && pairIdFromSharePath(url.pathname)) {
      return this.handleShareUpload(request, pairId);
    }

    if (shareCommand) {
      return this.handleShareCommand(request, pairId, shareCommand.command);
    }

    if (request.headers.get("upgrade") === "websocket") {
      return this.handleWebSocket();
    }

    if (request.method === "GET") {
      return jsonResponse({ latest: null, mode: "live-only" });
    }

    return textResponse("Expected WebSocket upgrade", 426);
  }

  private handleWebSocket(): Response {
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];

    server.accept();
    this.sessions.add(server);

    server.addEventListener("message", (event) => this.onMessage(server, event.data));
    server.addEventListener("close", () => this.sessions.delete(server));
    server.addEventListener("error", () => this.sessions.delete(server));

    return new Response(null, { status: 101, webSocket: client });
  }

  private async handleShareUpload(request: Request, pairId: string): Promise<Response> {
    if (!this.env.FILES) return textResponse("File storage is not configured", 503);
    if (!this.env.RELAY_TOKEN) return textResponse("Relay token secret is not configured", 503);

    const contentLength = Number(request.headers.get("content-length") ?? 0);
    if (!Number.isFinite(contentLength) || contentLength <= 0) {
      return textResponse("Missing file body", 400);
    }
    if (contentLength > MAX_SHARED_FILE_BYTES) {
      return textResponse("File too large", 413);
    }
    if (contentLength > MAX_SINGLE_WORKER_UPLOAD_BYTES) {
      return textResponse("Use multipart share endpoints for files over 95 MB", 413);
    }

    const id = crypto.randomUUID();
    const fileName = sanitizeFileName(request.headers.get("x-file-name"));
    const mime = request.headers.get("content-type")?.split(";")[0]?.trim() || "application/octet-stream";
    const expiresAt = Date.now() + SHARE_LINK_TTL_MS;
    const key = sharedFileKey(pairId, id, fileName);

    await this.env.FILES.put(key, request.body, {
      httpMetadata: {
        contentType: mime,
        cacheControl: "private, max-age=60"
      },
      customMetadata: {
        pairId,
        id,
        name: fileName,
        expiresAt: String(expiresAt)
      }
    });

    const baseUrl = new URL(request.url).origin;
    const link = shareLink(baseUrl, pairId, id, fileName);
    const fromDevice = request.headers.get("x-device-id") || "xloidflare-share";
    const packet = await buildEncryptedTextPacket(pairId, this.env.RELAY_TOKEN, fromDevice, link, expiresAt);
    this.broadcast(null, packet);

    console.log(JSON.stringify({
      event: "file_share",
      pairId,
      clipId: packet.clipId,
      fromDevice,
      fileName,
      mime,
      bytes: contentLength,
      expiresAt
    }));

    return jsonResponse({ ok: true, url: link, expiresAt, bytes: contentLength, name: fileName, mime });
  }

  private async handleShareCommand(request: Request, pairId: string, command: string): Promise<Response> {
    if (command === "start" && request.method === "POST") {
      return this.handleMultipartStart(request, pairId);
    }
    if (command === "part" && (request.method === "PUT" || request.method === "POST")) {
      return this.handleMultipartPart(request, pairId);
    }
    if (command === "complete" && request.method === "POST") {
      return this.handleMultipartComplete(request, pairId);
    }
    if (command === "abort" && request.method === "POST") {
      return this.handleMultipartAbort(request, pairId);
    }
    return textResponse("Unsupported share command", 405);
  }

  private async readJson(request: Request): Promise<Record<string, any>> {
    try {
      return await request.json();
    } catch {
      throw new Error("Invalid JSON");
    }
  }

  private async handleMultipartStart(request: Request, pairId: string): Promise<Response> {
    if (!this.env.FILES) return textResponse("File storage is not configured", 503);

    const body = await this.readJson(request);
    const fileName = sanitizeFileName(body.name);
    const mime = typeof body.mime === "string" && body.mime.trim()
      ? body.mime.split(";")[0].trim()
      : "application/octet-stream";
    const bytes = Number(body.bytes ?? 0);
    if (!Number.isFinite(bytes) || bytes <= 0) return textResponse("Missing file size", 400);
    if (bytes > MAX_SHARED_FILE_BYTES) return textResponse("File too large", 413);

    const id = crypto.randomUUID();
    const expiresAt = Date.now() + SHARE_LINK_TTL_MS;
    const key = sharedFileKey(pairId, id, fileName);
    const upload = await this.env.FILES.createMultipartUpload(key, {
      httpMetadata: {
        contentType: mime,
        cacheControl: "private, max-age=60"
      },
      customMetadata: {
        pairId,
        id,
        name: fileName,
        bytes: String(bytes),
        expiresAt: String(expiresAt)
      }
    });

    return jsonResponse({
      ok: true,
      id,
      uploadId: upload.uploadId,
      name: fileName,
      mime,
      expiresAt,
      partSize: MULTIPART_CHUNK_BYTES,
      minPartSize: MIN_MULTIPART_PART_BYTES,
      maxPartSize: MAX_SINGLE_WORKER_UPLOAD_BYTES,
      maxBytes: MAX_SHARED_FILE_BYTES
    });
  }

  private async handleMultipartPart(request: Request, pairId: string): Promise<Response> {
    if (!this.env.FILES) return textResponse("File storage is not configured", 503);

    const url = new URL(request.url);
    const id = url.searchParams.get("id") || request.headers.get("x-file-id") || "";
    const uploadId = url.searchParams.get("uploadId") || request.headers.get("x-upload-id") || "";
    const fileName = sanitizeFileName(url.searchParams.get("name") || request.headers.get("x-file-name"));
    const partNumber = Number(url.searchParams.get("partNumber") || request.headers.get("x-part-number") || 0);
    const contentLength = Number(request.headers.get("content-length") ?? 0);

    if (!/^[A-Za-z0-9_-]+$/.test(id) || !uploadId) return textResponse("Missing multipart id", 400);
    if (!Number.isInteger(partNumber) || partNumber < 1 || partNumber > 10000) return textResponse("Invalid part number", 400);
    if (!Number.isFinite(contentLength) || contentLength <= 0) return textResponse("Missing part body", 400);
    if (contentLength > MAX_SINGLE_WORKER_UPLOAD_BYTES) return textResponse("Part too large", 413);

    const upload = this.env.FILES.resumeMultipartUpload(sharedFileKey(pairId, id, fileName), uploadId);
    const part = await upload.uploadPart(partNumber, request.body);
    return jsonResponse({ ok: true, partNumber: part.partNumber, etag: part.etag });
  }

  private async handleMultipartComplete(request: Request, pairId: string): Promise<Response> {
    if (!this.env.FILES) return textResponse("File storage is not configured", 503);
    if (!this.env.RELAY_TOKEN) return textResponse("Relay token secret is not configured", 503);

    const body = await this.readJson(request);
    const id = String(body.id || "");
    const uploadId = String(body.uploadId || "");
    const fileName = sanitizeFileName(body.name);
    const mime = typeof body.mime === "string" && body.mime.trim()
      ? body.mime.split(";")[0].trim()
      : "application/octet-stream";
    const bytes = Number(body.bytes ?? 0);
    const parts = Array.isArray(body.parts) ? body.parts : [];
    if (!/^[A-Za-z0-9_-]+$/.test(id) || !uploadId || parts.length === 0) return textResponse("Missing multipart completion data", 400);

    const normalizedParts = parts.map((part) => ({
      partNumber: Number(part.partNumber),
      etag: String(part.etag || "")
    }));
    if (normalizedParts.some((part) => !Number.isInteger(part.partNumber) || part.partNumber < 1 || !part.etag)) {
      return textResponse("Invalid multipart parts", 400);
    }

    const key = sharedFileKey(pairId, id, fileName);
    const upload = this.env.FILES.resumeMultipartUpload(key, uploadId);
    await upload.complete(normalizedParts);
    const head = await this.env.FILES.head(key);
    const expiresAt = Number(head?.customMetadata?.expiresAt ?? Date.now() + SHARE_LINK_TTL_MS);

    const baseUrl = new URL(request.url).origin;
    const link = shareLink(baseUrl, pairId, id, fileName);
    const fromDevice = request.headers.get("x-device-id") || String(body.fromDevice || "xloidflare-share");
    const packet = await buildEncryptedTextPacket(pairId, this.env.RELAY_TOKEN, fromDevice, link, expiresAt);
    this.broadcast(null, packet);

    console.log(JSON.stringify({
      event: "file_share_multipart",
      pairId,
      clipId: packet.clipId,
      fromDevice,
      fileName,
      mime,
      bytes: bytes || head?.size || 0,
      parts: normalizedParts.length,
      expiresAt
    }));

    return jsonResponse({
      ok: true,
      url: link,
      expiresAt,
      bytes: bytes || head?.size || 0,
      name: fileName,
      mime,
      parts: normalizedParts.length
    });
  }

  private async handleMultipartAbort(request: Request, pairId: string): Promise<Response> {
    if (!this.env.FILES) return textResponse("File storage is not configured", 503);

    const body = await this.readJson(request);
    const id = String(body.id || "");
    const uploadId = String(body.uploadId || "");
    const fileName = sanitizeFileName(body.name);
    if (!/^[A-Za-z0-9_-]+$/.test(id) || !uploadId) return textResponse("Missing multipart abort data", 400);

    const upload = this.env.FILES.resumeMultipartUpload(sharedFileKey(pairId, id, fileName), uploadId);
    await upload.abort();
    return jsonResponse({ ok: true });
  }

  private async onMessage(sender: WebSocket, data: string | ArrayBuffer): Promise<void> {
    if (typeof data !== "string" || data.length > MAX_MESSAGE_LENGTH) {
      sender.close(1009, "Message too large");
      return;
    }

    let message: ClientMessage;
    try {
      message = JSON.parse(data);
    } catch {
      sender.close(1003, "Invalid JSON");
      return;
    }

    if (message.kind === "ping") {
      sender.send(JSON.stringify({ kind: "pong", at: Date.now() }));
      return;
    }

    if (message.kind === "ack") {
      this.broadcast(sender, message);
      return;
    }

    if (!this.isValidClip(message)) {
      sender.close(1003, "Invalid clip packet");
      return;
    }

    await this.debugLogText(message);
    this.broadcast(sender, message);
  }

  private async debugLogText(message: ClipPacket): Promise<void> {
    if (this.env.DEBUG_LOG_TEXT !== "true" || !this.env.RELAY_TOKEN) return;
    if (message.mime !== TEXT_MIME || !this.pairId) return;

    try {
      const text = await decryptTextPacket(this.pairId, this.env.RELAY_TOKEN, message);
      const maxLength = 2048;
      console.log(JSON.stringify({
        event: "clip_text",
        pairId: this.pairId,
        clipId: message.clipId,
        fromDevice: message.fromDevice,
        createdAt: message.createdAt,
        text: text.slice(0, maxLength),
        truncated: text.length > maxLength
      }));
    } catch (error) {
      console.log(JSON.stringify({
        event: "clip_text_decrypt_failed",
        pairId: this.pairId,
        clipId: message.clipId,
        fromDevice: message.fromDevice,
        error: `${error}`
      }));
    }
  }

  private broadcast(sender: WebSocket | null, message: ClientMessage): void {
    const payload = JSON.stringify(message);
    for (const session of this.sessions) {
      if (session !== sender) {
        try {
          session.send(payload);
        } catch {
          this.sessions.delete(session);
        }
      }
    }
  }

  private isValidClip(packet: ClipPacket): boolean {
    const now = Date.now();
    if (packet.kind !== "clip"
      || typeof packet.clipId !== "string"
      || typeof packet.fromDevice !== "string"
      || !isAllowedMime(packet.mime)
      || typeof packet.nonce !== "string"
      || !Number.isFinite(packet.createdAt)
      || !Number.isFinite(packet.expiresAt)
      || packet.expiresAt <= now
      || packet.expiresAt - now > MAX_CLIP_TTL_MS) {
      return false;
    }

    if (packet.mime === TEXT_MIME) {
      return typeof packet.ciphertext === "string" && packet.ciphertext.length <= MAX_TEXT_CIPHERTEXT_LENGTH;
    }

    return typeof packet.ciphertext === "string"
      && packet.ciphertext.length > 0
      && packet.ciphertext.length <= MAX_IMAGE_CIPHERTEXT_LENGTH;
  }
}
