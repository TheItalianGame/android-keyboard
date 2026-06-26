#!/data/data/com.termux/files/usr/bin/sh
set -eu

ACCOUNT_ID="429d20e2971fc2ad19c63b4780c18b3b"
BUCKET_NAME="xloidflare-shares"

printf 'R2 S3 access key ID: ' >&2
IFS= read -r R2_ACCESS_KEY_ID
stty -echo
printf 'R2 S3 secret access key: ' >&2
IFS= read -r R2_SECRET_ACCESS_KEY
stty echo
printf '\nCreating R2 bucket %s via S3 API...\n' "$BUCKET_NAME" >&2

export ACCOUNT_ID BUCKET_NAME R2_ACCESS_KEY_ID R2_SECRET_ACCESS_KEY

node <<'NODE'
const crypto = require("crypto");

const accountId = process.env.ACCOUNT_ID;
const bucketName = process.env.BUCKET_NAME;
const accessKeyId = process.env.R2_ACCESS_KEY_ID;
const secretAccessKey = process.env.R2_SECRET_ACCESS_KEY;

function hmac(key, value) {
  return crypto.createHmac("sha256", key).update(value).digest();
}

function hash(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function isoBasic(date) {
  return date.toISOString().replace(/[:-]|\.\d{3}/g, "");
}

function signingKey(secret, dateStamp, region, service) {
  const kDate = hmac(`AWS4${secret}`, dateStamp);
  const kRegion = hmac(kDate, region);
  const kService = hmac(kRegion, service);
  return hmac(kService, "aws4_request");
}

async function createBucket() {
  const method = "PUT";
  const region = "auto";
  const service = "s3";
  const now = new Date();
  const amzDate = isoBasic(now);
  const dateStamp = amzDate.slice(0, 8);
  const host = `${accountId}.r2.cloudflarestorage.com`;
  const path = `/${bucketName}`;
  const payloadHash = hash("");
  const credentialScope = `${dateStamp}/${region}/${service}/aws4_request`;
  const canonicalHeaders =
    `host:${host}\n` +
    `x-amz-content-sha256:${payloadHash}\n` +
    `x-amz-date:${amzDate}\n`;
  const signedHeaders = "host;x-amz-content-sha256;x-amz-date";
  const canonicalRequest = [
    method,
    path,
    "",
    canonicalHeaders,
    signedHeaders,
    payloadHash
  ].join("\n");
  const stringToSign = [
    "AWS4-HMAC-SHA256",
    amzDate,
    credentialScope,
    hash(canonicalRequest)
  ].join("\n");
  const signature = crypto
    .createHmac("sha256", signingKey(secretAccessKey, dateStamp, region, service))
    .update(stringToSign)
    .digest("hex");
  const authorization =
    `AWS4-HMAC-SHA256 Credential=${accessKeyId}/${credentialScope}, ` +
    `SignedHeaders=${signedHeaders}, Signature=${signature}`;

  const response = await fetch(`https://${host}${path}`, {
    method,
    headers: {
      authorization,
      host,
      "x-amz-content-sha256": payloadHash,
      "x-amz-date": amzDate
    }
  });
  const text = await response.text();
  console.log(JSON.stringify({
    status: response.status,
    ok: response.ok || response.status === 409,
    body: text
  }, null, 2));
  if (!response.ok && response.status !== 409) process.exit(1);
}

createBucket().catch((error) => {
  console.error(error);
  process.exit(1);
});
NODE
