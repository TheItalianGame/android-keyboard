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
printf '\n'
stty -echo
printf 'R2 S3 access key ID: ' >&2
IFS= read -r R2_ACCESS_KEY_ID
stty echo
printf '\n'
stty -echo
printf 'R2 S3 secret access key: ' >&2
IFS= read -r R2_SECRET_ACCESS_KEY
stty echo
printf '\nDeploying %s Worker...\n' "$SCRIPT_NAME" >&2

cleanup() {
  rm -f metadata.deploy.tmp.json
}
trap cleanup EXIT

RELAY_TOKEN=$(cat .relay-token)
if [ -f .relay-token.sha256.tmp ]; then
  RELAY_HASH=$(cat .relay-token.sha256.tmp)
else
  RELAY_HASH=$(printf '%s' "$RELAY_TOKEN" | sha256sum | awk '{print $1}')
fi
export RELAY_TOKEN RELAY_HASH BUCKET_NAME ACCOUNT_ID R2_ACCESS_KEY_ID R2_SECRET_ACCESS_KEY

node -e 'const fs=require("fs"); const metadata={main_module:"index.js",compatibility_date:"2026-06-23",bindings:[{type:"durable_object_namespace",name:"ROOMS",class_name:"ClipboardRoom"},{type:"r2_bucket",name:"FILES",bucket_name:process.env.BUCKET_NAME},{type:"plain_text",name:"R2_ACCOUNT_ID",text:process.env.ACCOUNT_ID},{type:"secret_text",name:"R2_ACCESS_KEY_ID",text:process.env.R2_ACCESS_KEY_ID},{type:"secret_text",name:"R2_SECRET_ACCESS_KEY",text:process.env.R2_SECRET_ACCESS_KEY},{type:"secret_text",name:"RELAY_TOKEN_SHA256",text:process.env.RELAY_HASH},{type:"secret_text",name:"RELAY_TOKEN",text:process.env.RELAY_TOKEN},{type:"plain_text",name:"DEBUG_LOG_TEXT",text:"true"}],annotations:{"workers/message":"Add direct R2 multipart file sharing links"}}; fs.writeFileSync("metadata.deploy.tmp.json", JSON.stringify(metadata));'

curl --silent --show-error --fail-with-body \
  -X PUT "${API_BASE}/workers/scripts/${SCRIPT_NAME}" \
  -H "Authorization: Bearer ${CF_API_TOKEN}" \
  -F 'metadata=@metadata.deploy.tmp.json;type=application/json' \
  -F 'index.js=@src/index.js;type=application/javascript+module' \
  > ../../logs/xloidflare-r2-worker-upload-response.json

curl --silent --show-error --fail-with-body \
  -X PUT "${API_BASE}/workers/scripts/${SCRIPT_NAME}/schedules" \
  -H "Authorization: Bearer ${CF_API_TOKEN}" \
  -H "Content-Type: application/json" \
  --data '[{"cron":"0 * * * *"}]' \
  > ../../logs/xloidflare-r2-schedules-response.json

printf 'Deploy complete.\n' >&2
