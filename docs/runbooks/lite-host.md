# Lite host runbook (P6.D)

The always-on demo: one Oracle Cloud Always Free A1 VM (1 OCPU, 6 GB, Ubuntu 24.04 arm64) running
`deploy/lite/compose.yml` behind Caddy, with real AWS SNS/SQS/S3/SES (`infra/aws/shared`). No availability claims.

## First setup
1. **OCI** (`infra/oci`): `terraform.tfvars` with `tenancy_ocid`, `region` (your home region), `admin_cidr` (`curl -s ifconfig.me`/32),
   then `terraform plan -out tfplan`, review (VCN, subnet, security list, 1 A1.Flex 1/6, bucket, PAR, lifecycle policy:
   all Always Free), `terraform apply tfplan`. On "Out of host capacity": `./retry-apply.sh` (same region and shape).
2. **DuckDNS**: point `convoy-demo.duckdns.org` at `terraform output public_ip`.
3. **Host**: `scp deploy/lite/bootstrap.sh ubuntu@IP:` then
   `ssh ubuntu@IP 'sudo DEPLOY_PUBKEY="$(cat deploy_key.pub)" bash bootstrap.sh'` (idempotent; re-run after changes).
4. **Env file**: copy `deploy/lite/convoy.env.example` to `/opt/convoy/convoy.env` (owner `deploy`, mode 600) and fill it
   from the `shared` and `oci` outputs; random secrets with `openssl rand -base64 24`; `AGE_RECIPIENT` from
   `age-keygen -o convoy-backup.key` on your laptop (the private key never goes to the host).
5. **GHCR**: make the `convoy-*` packages public (repo Packages settings), or `docker login ghcr.io` as `deploy` with a read-only token.
6. **GitHub environment `lite`**: secrets `LITE_HOST`, `LITE_SSH_KEY` (private half of `deploy_key`), `LITE_KNOWN_HOSTS`
   (`ssh-keyscan IP`, checked against the console fingerprint); variable `LITE_SITE=convoy-demo.duckdns.org`.
7. First deploy: Actions, `deploy-lite`, run with the latest `images` run id.

## Deploys
`deploy-lite` runs after every successful `images` build on main: `ssh deploy@host "deploy <sha>" < images.env`.
The key can only run `deploy-ssh.sh`, which accepts only that command and digests from this repo's GHCR. `deploy.sh`:
pre-deploy backup, checkout of the commit, pull by digest, one service at a time until healthy (Flyway migrates on
start), smoke test (home page, catalogue API, `/actuator` unreachable), automatic rollback to the previous release.
Migrations must be backward compatible (expand now, contract in a later release): rollback never touches the database.

## Backups and restore
Nightly 02:30 IST: `backup.sh` (pg_dumpall, gzip, `age` to the public key, upload through the PAR; 7 days kept by the
bucket lifecycle rule; last 2 on the host). Restore drill:
```sh
scp convoy-backup.key ubuntu@<ip>:/tmp/k
ssh ubuntu@<ip> 'sudo chown deploy /tmp/k && sudo -u deploy /opt/convoy/src/deploy/lite/restore-check.sh /tmp/k; sudo shred -u /tmp/k'
```
It loads the newest backup into a scratch Postgres container and diffs the row count of every table with the live one.
A real restore: stop the services, `age -d` + `gunzip` into `psql` on the live container, start the services.

## Checks on the live VM (P6.D, recorded in the P6 report)
| Check | Command |
|---|---|
| Demo journey through the public URL | `cd web && WEB_URL=https://convoy-demo.duckdns.org REMOTE=1 pnpm exec playwright test e2e/demo.spec.ts --project=mobile-360` |
| Reboot back to healthy | `sudo reboot`; then `docker ps` (all healthy, `restart: unless-stopped`) and the journey again |
| Only 80/443 open from outside | `nmap -Pn -p- --min-rate 2000 <ip>` from a machine other than `admin_cidr`: 80, 443 open; 22 filtered |
| Internals unreachable | `curl -s -o /dev/null -w '%{http_code}' https://<site>/actuator/prometheus` and `/metrics`, `/api/admin/audit` without a session: not 200 |
| TLS valid | `curl -vI https://<site> 2>&1 \| grep -E 'issuer\|expire'` (Let's Encrypt), `openssl s_client` chain verify OK |
| Memory per container | `docker stats --no-stream` idle and during the journey (VM numbers, reported apart from laptop numbers) |

Oracle reclaims idle Always Free VMs when CPU, network *and* memory (A1) stay under 20 % for 7 days. Lite idles at
≈ 39 % memory (2.3 GiB of 6 GB, laptop measurement), so it does not meet the idle criteria. Never generate fake load.

## When something is wrong
- A service won't start under prod: its log names the problem (`prod config invalid: ...` or `Topology.Missing: ...`).
  Fix the env file or apply `infra/aws/shared`; never switch the profile.
- `ROLLBACK FAILED`: `cat /opt/convoy/deployed/sha`, check out that commit, `docker compose ... --env-file /opt/convoy/deployed/images.env up -d`.
- DLQ alarm email: `docs/runbooks/dlq-redrive.md`.
