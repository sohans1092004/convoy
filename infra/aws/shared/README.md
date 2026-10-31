# infra/aws/shared (P6.C)

AWS resources the lite host uses, which the full profile reuses: SNS/SQS exactly as the services create them locally
(`topology.json`), private encrypted buckets, optional SES, the host's least-privilege IAM user, a budget on Convoy's own spend, and the full profile's state
bucket. Every resource is tagged `project=convoy`, `profile=lite` and named `convoy-…`.
Cost: [docs/cost/aws-cost-estimate.md §6](../../../docs/cost/aws-cost-estimate.md).

**The account is shared with Concord** (project "concord", region `ap-southeast-2`, the only region an SCP allows).
Terraform here manages only what it creates; a plan must show nothing but `convoy-` resources and 0 changes or
destroys, otherwise stop. Access is your `aws login` session, profile `convoy-aws` (12 h). No GitHub OIDC provider:
the organisation SCP denies `iam:CreateOpenIDConnectProvider`.

## Apply (you run it)
```sh
cd infra/aws/shared
export AWS_PROFILE=convoy-aws      # if Terraform does not pick the profile up:
                                   # eval "$(aws configure export-credentials --profile convoy-aws --format env)"
cat > terraform.tfvars <<EOF        # git-ignored
site_origin = "https://convoy-demo.duckdns.org"
alert_email = "you@example.com"
mail_from   = "you@example.com"      # verify it in SES (AWS emails a link) before the first send
# ses_domain = "example.com"         # only with a domain you control
# queue_age_consumers = ["payment", "order"]   # default: age alarms only on the money path
EOF
terraform init
terraform plan -out tfplan            # review: only convoy- resources, 0 to change, 0 to destroy
terraform apply tfplan
terraform output                      # values for /opt/convoy/convoy.env; secrets with -raw (below)
```
Then confirm the two subscription emails AWS sends (alerts topic, budget).

`topology.json` comes from the code: `docker compose up -d && python scripts/dump-topology.py` (local stack, every
service started once). Re-run it when a service subscribes to a new event type; a mismatch otherwise stops that
service at startup with the missing queue, subscription or filter type named (`Topology.Missing`).

## Host credentials (not in Terraform)
Terraform creates the two users and their policies only. Their access keys would sit in the Terraform state, so they are
made by a script that writes them straight into the git-ignored env file for the VM, never to the terminal:
```sh
AWS_PROFILE=convoy-aws scripts/create-host-keys.sh        # -> deploy/lite/convoy.env (git-ignored, mode 600)
```
It creates a key for `convoy-lite-host` (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY) and one for `convoy-lite-smtp`,
from which it derives the SES SMTP password (SMTP_USERNAME / SMTP_PASSWORD). Copy the file to the VM as
`/opt/convoy/convoy.env` (owner `deploy`, mode 600) and delete the local copy if you like.

**State:** `infra/aws/shared/terraform.tfstate`, local and git-ignored (`*.tfstate`). With the keys outside Terraform it
holds no secrets: names, ARNs, policies, the alert email. Keep a private copy anyway (it is the only record for `destroy`).

## Rotating a key
1. `AWS_PROFILE=convoy-aws scripts/create-host-keys.sh` writes a new key for both users into the env file
   (it refuses if a user already has 2 keys, the IAM limit).
2. Copy the file to the VM and restart: `docker compose --env-file /opt/convoy/convoy.env --env-file /opt/convoy/deployed/images.env -f deploy/lite/compose.yml up -d`.
3. Delete the old keys: `aws iam list-access-keys --user-name convoy-lite-host --profile convoy-aws`, then
   `aws iam delete-access-key --user-name convoy-lite-host --access-key-id <old> --profile convoy-aws` (same for convoy-lite-smtp).
Rotate every 90 days, or at once if the env file may have leaked.
