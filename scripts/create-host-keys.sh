#!/usr/bin/env bash
# Create the lite host's AWS access key (convoy-lite-host) and SES SMTP credentials (convoy-lite-smtp) and write them
# straight into the git-ignored env file for the VM. Nothing is printed and nothing reaches Terraform state: the CLI's
# JSON goes through a pipe into python, which writes the file (mode 600) and derives the SMTP password from the key.
#   scripts/create-host-keys.sh [env-file]        (default deploy/lite/convoy.env, git-ignored; copy it to the VM as
#                                                   /opt/convoy/convoy.env, docs/runbooks/lite-host.md)
# Rotation: run it again. It refuses when a user already has 2 keys (the IAM limit); delete the old key after the VM
# runs on the new one:  aws iam delete-access-key --user-name convoy-lite-host --access-key-id <old id> --profile convoy-aws
set -euo pipefail
cd "$(dirname "$0")/.."
env_file=${1:-deploy/lite/convoy.env}
profile=${AWS_PROFILE:-convoy-aws}
py=$(command -v python3 || command -v python)
git check-ignore -q "$env_file" || { echo "refused: $env_file is not git-ignored"; exit 2; }
[ -f "$env_file" ] || install -m 600 deploy/lite/convoy.env.example "$env_file"
chmod 600 "$env_file"

for user in convoy-lite-host convoy-lite-smtp; do
  n=$(aws iam list-access-keys --user-name "$user" --profile "$profile" --query 'length(AccessKeyMetadata)' --output text)
  [ "$n" -lt 2 ] || { echo "refused: $user already has 2 keys; delete the unused one first"; exit 2; }
done

write() { # user kind -> the new key goes into env_file through a pipe, never to the terminal
  aws iam create-access-key --user-name "$1" --profile "$profile" --output json |
    "$py" -I -c '
import base64, hashlib, hmac, json, re, sys
kind, path, region = sys.argv[1], sys.argv[2], sys.argv[3]
k = json.load(sys.stdin)["AccessKey"]
def smtp_password(secret):  # AWS SES SMTP password (Signature V4), documented algorithm
    sig = ("AWS4" + secret).encode()
    for m in ("11111111", region, "ses", "aws4_request", "SendRawEmail"):
        sig = hmac.new(sig, m.encode(), hashlib.sha256).digest()
    return base64.b64encode(b"\x04" + sig).decode()
values = {"AWS_ACCESS_KEY_ID": k["AccessKeyId"], "AWS_SECRET_ACCESS_KEY": k["SecretAccessKey"]} if kind == "host" \
    else {"SMTP_USERNAME": k["AccessKeyId"], "SMTP_PASSWORD": smtp_password(k["SecretAccessKey"])}
text = open(path, encoding="utf-8").read()
for name, value in values.items():
    line = f"{name}={value}"
    text, n = re.subn(rf"^{name}=.*$", lambda _: line, text, flags=re.M)
    if n == 0: text += ("" if text.endswith("\n") else "\n") + line + "\n"
open(path, "w", encoding="utf-8").write(text)
kid = k["AccessKeyId"]
print(f"{kind}: new key written to {path} (id ending ...{kid[-4:]})")
' "$2" "$env_file" "${AWS_REGION:-ap-southeast-2}"
}
write convoy-lite-host host
write convoy-lite-smtp smtp
echo "done. Never commit $env_file; copy it to the VM (scp, then chmod 600, owner deploy)."
