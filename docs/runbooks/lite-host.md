# Lite host runbook (P6.D)

The always-on demo: one Oracle Cloud Always Free A1 VM (1 OCPU, 6 GB, Ubuntu 24.04 arm64) running
`deploy/lite/compose.yml` behind Caddy, with real AWS SNS/SQS/S3/SES (`infra/aws/shared`). No availability claims.

## First setup
1. **OCI** (`infra/oci`): `terraform.tfvars` with `tenancy_ocid`, `region` (your home region), `admin_cidr` (`curl -s ifconfig.me`/32),
   then `terraform plan -out tfplan`, review (VCN, subnet, security list, 1 A1.Flex 1/6, bucket, PAR, lifecycle policy:
   all Always Free), `terraform apply tfplan`. On "Out of host capacity": `./retry-apply.sh` (same region and shape).
   Then `scripts/create-backup-par.sh` (the VM's write-only backup link, into the env file).
2. **DuckDNS**: point `convoy-demo.duckdns.org` at `terraform output public_ip`.
3. **Host**: `scp deploy/lite/bootstrap.sh ubuntu@IP:` then
   `ssh ubuntu@IP 'sudo DEPLOY_PUBKEY="$(cat deploy_key.pub)" bash bootstrap.sh'` (idempotent; re-run after changes).
4. **Env file**: copy `deploy/lite/convoy.env.example` to `/opt/convoy/convoy.env` (owner `deploy`, mode 600) and fill it
   from the `shared` and `oci` outputs; random secrets with `openssl rand -base64 24`; `AGE_RECIPIENT` from
   `age-keygen -o convoy-backup.key` on your laptop (the private key never goes to the host).
5. **GHCR**: make the `convoy-*` packages public (repo Packages settings), or `docker login ghcr.io` as `deploy` with a read-only token.
6. **Deploy key** on the laptop: `~/.ssh/convoy-deploy` (its public half is `DEPLOY_PUBKEY` above).
7. First deploy: `scripts/deploy-lite.sh <sha>` (below).

## Deploys (from the owner's laptop only)
The host accepts SSH only from the owner's IP (`admin_cidr`); port 22 is never opened to GitHub runners. The
`deploy-lite` workflow therefore never runs on push, and started by hand it fails at once and points here.

1. Merge to `main` and wait for the `images` workflow on that commit to succeed (multi-arch images by digest, and the
   web image carries the short SHA and build time shown at `/version` and in the footer).
2. `scripts/deploy-lite.sh <sha>` (needs `gh` signed in and `~/.ssh/convoy-deploy`). It downloads that run's
   `images.env` and runs `ssh deploy@host "deploy <sha>" < images.env`, then prints `/version` from the public site.
3. Afterwards: the public demo journey
   (`cd web && WEB_URL=https://<site> pnpm exec playwright test e2e/demo.spec.ts --project=mobile-360`).

The deploy key can only run `deploy-ssh.sh`, which accepts only `deploy <40-hex sha>` and digests from this repo's GHCR.
`deploy.sh`: pre-deploy backup, checkout of the commit, pull by digest, one service at a time until healthy (Flyway
migrates on start), smoke test (home page, catalogue API, `/actuator` unreachable), automatic rollback to the previous
release. Migrations must be backward compatible (expand now, contract in a later release): rollback never touches the
database.

### "My IP changed" (SSH times out)
The laptop's public IP comes from the carrier and changes. Symptom: `ssh: connect to host ... port 22: Connection timed
out` (the site itself still answers). Fix: allow the new IP in the host's security group through Terraform.
```bash
curl -s https://checkip.amazonaws.com                       # the new IP
aws login --profile convoy-aws
cd infra/aws/lite-ec2
eval "$(aws configure export-credentials --profile convoy-aws --format env)"; export AWS_REGION=ap-southeast-2
T=../../../tools/oci-shim/terraform                         # Terraform in Docker
$T init -input=false -reconfigure -backend-config="bucket=convoy-tfstate-$(aws sts get-caller-identity --query Account --output text)"
$T plan -target=aws_security_group.host -var "admin_cidr=<new IP>/32" -var "ssh_public_key=$(cat ~/.ssh/convoy-ec2.pub)" -out sg.tfplan
```
Use `-target=aws_security_group.host` and check the plan says `0 to add, 1 to change, 0 to destroy` and changes only
the SSH ingress CIDR, then `$T apply sg.tfplan`. (Until P8 an untargeted plan wanted to *replace the live host* because
`associate_public_ip_address` drifted; the code now matches the host, `ami`/`user_data` changes are ignored, and the
instance and Elastic IP have `prevent_destroy`, so such a plan fails instead.) Only one IP is ever allowed. (`ssh_public_key` must be `convoy-ec2.pub`.)

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
scripts/restore-check.sh ~/convoy-backup.key <vm-ip>        # newest backup; or add an object name
```
It downloads the backup, decrypts it in a throwaway container, loads it into a scratch Postgres here and diffs the row
count of every table with the live database on the VM (over SSH). A real restore: the same download and decrypt, then
`psql` into the VM's Postgres with the services stopped.

## Checks on the live VM (P6.D, recorded in the P6 report)
| Check | Command |
|---|---|
| Demo journey through the public URL | `cd web && WEB_URL=https://convoy-demo.duckdns.org REMOTE=1 pnpm exec playwright test e2e/demo.spec.ts --project=mobile-360` |
| Reboot back to healthy | `sudo reboot`; then `docker ps` (all healthy, `restart: unless-stopped`) and the journey again |
| Only 80/443 open from outside | `nmap -Pn -p- --min-rate 2000 <ip>` from a machine other than `admin_cidr`: 80, 443 open; 22 filtered |
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
