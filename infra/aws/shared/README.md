# infra/aws/shared (P6.C)

AWS resources the lite host uses, which the full profile reuses: SNS/SQS exactly as the services create them locally
(`topology.json`), private encrypted buckets, optional SES, the host's least-privilege IAM user, budget alerts, and for
the full profile its state bucket and GitHub OIDC role. Every resource is tagged `project=convoy`, `profile=lite`.
Cost: [docs/cost/aws-cost-estimate.md §6](../../../docs/cost/aws-cost-estimate.md) (≈ $7.26/month, ≈ $3.96 without queue-age alarms).

## Apply (you run it)
```sh
cd infra/aws/shared
cat > terraform.tfvars <<EOF        # git-ignored
site_origin = "https://convoy-demo.duckdns.org"
alert_email = "you@example.com"
mail_from   = "you@example.com"      # verify it in SES (AWS emails a link) before the first send
# ses_domain = "example.com"         # only with a domain you control
# queue_age_alarms = false           # DLQ alarms only, ~$3.30/month less
EOF
terraform init
terraform plan -out tfplan            # review: ~180 resources, all tagged, nothing hourly-billed
terraform apply tfplan
terraform output                      # values for /opt/convoy/convoy.env; secrets with -raw (below)
```
Then confirm the two subscription emails AWS sends (alerts topic, budget).

`topology.json` comes from the code: `docker compose up -d && python scripts/dump-topology.py` (local stack, every
service started once). Re-run it when a service subscribes to a new event type; a mismatch otherwise stops that
service at startup with the missing queue, subscription or filter type named (`Topology.Missing`).

## Host credentials
```sh
terraform output -raw host_secret_access_key   # AWS_SECRET_ACCESS_KEY (AWS_ACCESS_KEY_ID = host_access_key_id)
terraform output -raw smtp_password            # SMTP_PASSWORD (SMTP_USERNAME = smtp_username)
```
The state file holds both: keep `terraform.tfstate` private (it is git-ignored), e.g. in a password manager.

## Rotating the lite host's access key
1. `terraform apply -replace=aws_iam_access_key.host` (a new key is created; the old one is deleted in the same apply).
2. On the host: put the new `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` in `/opt/convoy/convoy.env`, then
   `docker compose --env-file /opt/convoy/convoy.env --env-file /opt/convoy/deployed/images.env -f deploy/lite/compose.yml up -d`
   (services restart one by one through compose; a few seconds of SQS polling errors between steps 1 and 2 are retried).
3. For zero failed polls: create a second key in the console first, switch the host to it, then run step 1 and switch back.
Same for SMTP: `-replace=aws_iam_access_key.smtp`, then `SMTP_USERNAME` / `SMTP_PASSWORD`. Rotate every 90 days or at once
if the env file may have leaked.
