#!/data/data/com.termux/files/usr/bin/sh
set -eu

ACCOUNT_ID="429d20e2971fc2ad19c63b4780c18b3b"
SCRIPT_NAME="xloidflare"
BUCKET_NAME="xloidflare-shares"
API_BASE="https://api.cloudflare.com/client/v4/accounts/${ACCOUNT_ID}"

stty -echo
printf 'Cloudflare API token: ' >&2
IFS= read -r CF_API_TOKEN
stty echo
printf '\nDeploying %s with R2 file sharing...\n' "$SCRIPT_NAME" >&2

cleanup() {
  rm -f metadata.deploy.tmp.json \
    bucket-get-response.tmp.json \
    bucket-create-response.tmp.json \
    worker-upload-response.tmp.json \
    schedules-response.tmp.json
}
trap cleanup EXIT

curl --silent --show-error --fail-with-body \
  "${API_BASE}/r2/buckets/${BUCKET_NAME}" \
  -H "Authorization: Bearer ${CF_API_TOKEN}" \
  > bucket-get-response.tmp.json || {
    curl --silent --show-error --fail-with-body \
      -X POST "${API_BASE}/r2/buckets" \
      -H "Authorization: Bearer ${CF_API_TOKEN}" \
      -H "Content-Type: application/json" \
      --data "{\"name\":\"${BUCKET_NAME}\"}" \
      > bucket-create-response.tmp.json
  }

RELAY_TOKEN=$(cat .relay-token)
if [ -f .relay-token.sha256.tmp ]; then
  RELAY_HASH=$(cat .relay-token.sha256.tmp)
else
  RELAY_HASH=$(printf '%s' "$RELAY_TOKEN" | sha256sum | awk '{print $1}')
fi
export RELAY_TOKEN RELAY_HASH BUCKET_NAME

node -e 'const fs=require("fs"); const metadata={main_module:"index.js",compatibility_date:"2026-06-23",bindings:[{type:"durable_object_namespace",name:"ROOMS",class_name:"ClipboardRoom"},{type:"r2_bucket",name:"FILES",bucket_name:process.env.BUCKET_NAME},{type:"secret_text",name:"RELAY_TOKEN_SHA256",text:process.env.RELAY_HASH},{type:"secret_text",name:"RELAY_TOKEN",text:process.env.RELAY_TOKEN},{type:"plain_text",name:"DEBUG_LOG_TEXT",text:"true"}],annotations:{"workers/message":"Add transient R2 file sharing links"}}; fs.writeFileSync("metadata.deploy.tmp.json", JSON.stringify(metadata));'

curl --silent --show-error --fail-with-body \
  -X PUT "${API_BASE}/workers/scripts/${SCRIPT_NAME}" \
  -H "Authorization: Bearer ${CF_API_TOKEN}" \
  -F 'metadata=@metadata.deploy.tmp.json;type=application/json' \
  -F 'index.js=@src/index.js;type=application/javascript+module' \
  > worker-upload-response.tmp.json

curl --silent --show-error --fail-with-body \
  -X PUT "${API_BASE}/workers/scripts/${SCRIPT_NAME}/schedules" \
  -H "Authorization: Bearer ${CF_API_TOKEN}" \
  -H "Content-Type: application/json" \
  --data '[{"cron":"0 * * * *"}]' \
  > schedules-response.tmp.json

printf 'Deploy complete. Health should report files=true after propagation.\n' >&2
