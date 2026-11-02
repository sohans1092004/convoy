# Lite host runbook (P6.D)

The always-on demo: one Oracle Cloud Always Free A1 VM (1 OCPU, 6 GB, Ubuntu 24.04 arm64) running
`deploy/lite/compose.yml` behind Caddy, with real AWS SNS/SQS/S3/SES (`infra/aws/shared`). No availability claims.

## First setup
1. **OCI** (`infra/oci`): `terraform.tfvars` with `tenancy_ocid`, `region` (your home region), `admin_cidr` (`curl -s ifconfig.me`/32),
   then `terraform plan -out tfplan`, review (VCN, subnet, security list, 1 A1.Flex 1/6, bucket, PAR, lifecycle policy:
   all Always Free), `terraform apply tfplan`. On "Out of host capacity": `./retry-apply.sh` (same region and shape).
   Then `scripts/create-backup-par.sh` (the VM's write-only backup link, into the env file).
2. **DuckDNS**: point `convoy-demo.duckdns.org` at `terraform output public_ip`.
3. **Host**: on the EC2 host, open a shell with `aws ssm start-session` (below) and run `sudo bash bootstrap.sh`, with the
   file copied from `deploy/lite/` (idempotent; re-run after changes). An Oracle VM has no Systems Manager: see "If the demo moves to Oracle".
4. **Env file**: copy `deploy/lite/convoy.env.example` to `/opt/convoy/convoy.env` (owner `deploy`, mode 600) and fill it
   from the `shared` and `oci` outputs; random secrets with `openssl rand -base64 24`; `AGE_RECIPIENT` from
   `age-keygen -o convoy-backup.key` on your laptop (the private key never goes to the host).
5. **GHCR**: make the `convoy-*` packages public (repo Packages settings), or `docker login ghcr.io` as `deploy` with a read-only token.
6. First deploy: `scripts/deploy-lite.sh <sha>` (below).

## Deploys (from the owner's laptop only)
The EC2 host has no inbound SSH: port 22 is closed and only 80 and 443 are open. Deploys go through AWS Systems Manager
with the owner's `aws login` session, so the laptop's IP does not matter. The `deploy-lite` workflow never runs on push,
and started by hand it fails at once and points here.

1. Merge to `main` and wait for the `images` workflow on that commit to succeed (multi-arch images by digest, and the
   web image carries the short SHA and build time shown at `/version` and in the footer).
2. `aws login --profile convoy-aws`, then `scripts/deploy-lite.sh <sha>` (also needs `gh` signed in). It downloads that
   run's `images.env`, sends it through the `convoy-deploy` SSM document, waits for the result, prints the output, then
   prints `/version` from the public site.
3. Afterwards: the public demo journey
   (`cd web && WEB_URL=https://<site> pnpm exec playwright test e2e/demo.spec.ts --project=mobile-360`).

The `convoy-deploy` document takes only a 40-hex sha and base64 `images.env`, and runs `deploy-ssh.sh` as the `deploy`
user, which accepts only `deploy <40-hex sha>` and digests from this repo's GHCR. This limits the deploy path, not the
person: the admin role can still run any command on the host. CloudTrail records who sent each command, and
`aws ssm get-command-invocation` shows its output (the `deploy-ssh.sh` name is historical; nothing reaches it over SSH).
`deploy.sh`: pre-deploy backup, checkout of the commit, pull by digest, one service at a time until healthy (Flyway
migrates on start), smoke test (home page, catalogue API, `/actuator` unreachable), automatic rollback to the previous
release. Migrations must be backward compatible (expand now, contract in a later release): rollback never touches the
database.

### A shell on the host
```bash
aws login --profile convoy-aws
aws ssm start-session --profile convoy-aws --region ap-southeast-2 --target i-0829475b795c8667e
```
This needs the [Session Manager plugin](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html)
on the laptop, once. Deploys do not need it. No key or inbound port is involved, and `terraform output shell` prints the command.

### "SSM agent offline" (deploys fail, or the host is not `Online`)
Check: `aws ssm describe-instance-information --profile convoy-aws --region ap-southeast-2`. A freshly attached role can
take a while to be noticed, because the agent backs off; restarting the agent fixes that once you can reach the host.
This is the one failure with no shell left, because port 22 is closed and the EC2 serial console is deliberately off.
Recovery: temporarily allow SSH for your current IP only, through Terraform, then close it again.

1. `curl -s https://checkip.amazonaws.com`, then add this to `aws_security_group.host` in `infra/aws/lite-ec2/main.tf`:
   `ingress { description = "SSH, temporary"; from_port = 22; to_port = 22; protocol = "tcp"; cidr_blocks = ["<IP>/32"] }`, one attribute per line.
2. Set up Terraform (see below), then `$T plan -var "ssh_public_key=$(cat ~/.ssh/convoy-ec2.pub)" -out sg.tfplan`. It must say
   `0 to add, 1 to change, 0 to destroy` and change only that rule, then `$T apply sg.tfplan`.
3. `ssh -i ~/.ssh/convoy-ec2 ubuntu@52.64.115.0`, then `sudo snap restart amazon-ssm-agent`, and check the log at
   `/var/log/amazon/ssm/amazon-ssm-agent.log`.
4. Once the host is `Online`, remove the rule the same way, and confirm port 22 times out.

Terraform here runs in Docker (Docker Desktop's `docker.exe` must be running):
```bash
cd infra/aws/lite-ec2
eval "$(aws configure export-credentials --profile convoy-aws --format env)"; export AWS_REGION=ap-southeast-2
T=../../../tools/oci-shim/terraform
$T init -input=false -reconfigure -backend-config="bucket=convoy-tfstate-$(aws sts get-caller-identity --profile convoy-aws --query Account --output text)"
```
`ssh_public_key` must be `convoy-ec2.pub`: the instance's key pair cannot change without replacing the host, so it stays even
though no port accepts SSH in normal use. For the same reason the security group's description still mentions SSH; changing it
replaces the group. The instance and Elastic IP have `prevent_destroy`, so a plan that would replace them fails.

### If the demo moves to Oracle
Everything above is EC2 only. An instance profile gives an EC2 instance its Systems Manager identity, and an Oracle VM has none.
Reaching one through Systems Manager needs **SSM hybrid activation**, which is not built: an activation, an agent registered
with it, a service role, and a change to the `convoy-deploy` target. AWS's [pricing page](https://aws.amazon.com/systems-manager/pricing/),
read 2026-11-02, lists hybrid and multicloud usage from 30 September 2026 at $0.002 per Run Command invocation and $0.05 per Session
Manager session, unlike EC2, where it lists no additional charges. Without that, an Oracle VM falls back to SSH: `infra/oci` still
opens port 22 to `admin_cidr`, `bootstrap.sh` still installs the deploy key's forced command, and the previous `deploy-lite.sh` is in git history.

## Backups and restore
Nightly 02:30 IST: `backup.sh` on the VM (pg_dumpall, gzip, `age` to the public key, upload). The VM holds only a
**write-only** link (OCI pre-authenticated request, AnyObjectWrite, no listing, 90-day expiry), made outside Terraform
by `scripts/create-backup-par.sh` straight into the git-ignored env file. `convoy-backups` is versioned: an overwrite
or a delete leaves the old object as a previous version (kept 14 days; current backups 7 days, lifecycle rules).
Rotation: run `scripts/create-backup-par.sh` before `BACKUP_PAR_EXPIRES`, copy the env file to the VM, then
`scripts/create-backup-par.sh --cleanup`.

Restores run **from the laptop** with `~/.oci/config` (Python OCI SDK, `pip install --user oci`) and the age private
key, which never leaves the laptop:
```sh
aws login --profile convoy-aws
scripts/restore-check.sh ~/.config/convoy/age.key        # newest backup; or add an object name
```
It needs `docker` on the PATH. It downloads the backup, decrypts it in a throwaway container, loads it into a scratch Postgres
here and diffs the row count of every table with the live database (a read-only query through Systems Manager's
`AWS-RunShellScript`). Every differing table should have more rows live than in the backup. A real restore: the same download and decrypt, then
`psql` into the VM's Postgres with the services stopped.

## Checks on the live VM (P6.D, recorded in the P6 report)
| Check | Command |
|---|---|
| Demo journey through the public URL | `cd web && WEB_URL=https://convoy-demo.duckdns.org REMOTE=1 pnpm exec playwright test e2e/demo.spec.ts --project=mobile-360` |
| Reboot back to healthy | `sudo reboot`; then `docker ps` (all healthy, `restart: unless-stopped`) and the journey again |
| Only 80/443 open from outside | `nmap -Pn -p- --min-rate 2000 <ip>`: 80, 443 open; 22 filtered. The security group has no rule for 22 (`aws ec2 describe-security-groups`) |
| Internals unreachable | `curl -s -o /dev/null -w '%{http_code}' https://<site>/actuator/prometheus` and `/metrics`, `/api/admin/audit` without a session: not 200 |
| TLS valid | `curl -vI https://<site> 2>&1 \| grep -E 'issuer\|expire'` (Let's Encrypt), `openssl s_client` chain verify OK |
| Memory per container | `docker stats --no-stream` idle and during the journey (VM numbers, reported apart from laptop numbers) |
| Backup + restore | nightly (or `backup.sh manual`) on the VM, then `scripts/restore-check.sh` on the laptop |

**Always Free A1 is 2 OCPU / 12 GB per tenancy** (Oracle, since June 2026): convoy-lite takes 1 / 6, Reliant the other
1 / 6. `infra/oci` refuses any plan that would take the tenancy's A1 total above 2 / 12 (precondition on the instance).

Oracle reclaims idle Always Free VMs when CPU, network *and* memory (A1) stay under 20 % for 7 days. Lite idles at
≈ 39 % memory (2.3 GiB of 6 GB, laptop measurement), so it does not meet the idle criteria. Never generate fake load.

## When something is wrong
- A service won't start under prod: its log names the problem (`prod config invalid: ...` or `Topology.Missing: ...`).
  Fix the env file or apply `infra/aws/shared`; never switch the profile.
- `ROLLBACK FAILED`: `cat /opt/convoy/deployed/sha`, check out that commit, `docker compose ... --env-file /opt/convoy/deployed/images.env up -d`.
- DLQ alarm email: `docs/runbooks/dlq-redrive.md`.
