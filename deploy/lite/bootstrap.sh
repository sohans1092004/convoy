#!/usr/bin/env bash
# Prepare the Oracle Cloud A1 VM for Convoy lite (P6.D). Idempotent: safe to run again after an upgrade or a change here.
#   scp deploy/lite/bootstrap.sh ubuntu@<ip>: && ssh ubuntu@<ip> 'sudo DEPLOY_PUBKEY="ssh-ed25519 AAAA... ci" bash bootstrap.sh'
# What it does:
# - unattended security upgrades, fail2ban, SSH keys only (no passwords, no root login)
# - opens 80/443 in the VM's iptables (Oracle's Ubuntu image rejects everything but 22), persisted
# - a 2 GB swap file (swappiness 10)
# - Docker + compose, with log rotation
# - the restricted `deploy` user: its key can only run deploy-ssh.sh (GitHub Actions deploys through it)
# - a checkout of the repo at /opt/convoy/src (public; the host env file and backups live beside it, never in git)
# - cron: nightly encrypted backup, DuckDNS address refresh
# Caddy itself runs in the compose stack and gets its Let's Encrypt certificate on first start.
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "run as root (sudo)"; exit 1; }
REPO=${REPO:-https://github.com/sohans1092004/convoy.git}
export DEBIAN_FRONTEND=noninteractive

apt-get update -q
apt-get install -yq unattended-upgrades fail2ban docker.io docker-compose-v2 age iptables-persistent git curl jq
dpkg-reconfigure -f noninteractive unattended-upgrades
cat > /etc/apt/apt.conf.d/52convoy-upgrades <<'EOF'
Unattended-Upgrade::Automatic-Reboot "true";
Unattended-Upgrade::Automatic-Reboot-Time "04:30";
EOF

# SSH: keys only
cat > /etc/ssh/sshd_config.d/10-convoy.conf <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
X11Forwarding no
MaxAuthTries 3
EOF
systemctl reload ssh
cat > /etc/fail2ban/jail.d/convoy.conf <<'EOF'
[sshd]
enabled = true
maxretry = 5
bantime = 1h
EOF
systemctl enable --now fail2ban
systemctl restart fail2ban

# iptables: 80/443 before Oracle's final REJECT (the security list allows the same; both layers must agree)
for port in 80 443; do
  iptables -C INPUT -p tcp -m state --state NEW --dport "$port" -j ACCEPT 2>/dev/null \
    || iptables -I INPUT "$(iptables -L INPUT --line-numbers | awk '/REJECT/ {print $1; exit}')" -p tcp -m state --state NEW --dport "$port" -j ACCEPT
done
netfilter-persistent save

# 2 GB swap: a JVM spike must page, not kill a container
if ! swapon --show | grep -q /swapfile; then
  [ -f /swapfile ] || { fallocate -l 2G /swapfile; chmod 600 /swapfile; mkswap /swapfile; }
  swapon /swapfile
fi
grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
echo 'vm.swappiness=10' > /etc/sysctl.d/90-convoy.conf
sysctl -q --system

# Docker: bounded logs (the boot volume is 50 GB)
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<'EOF'
{ "log-driver": "local", "log-opts": { "max-size": "20m", "max-file": "3" } }
EOF
systemctl enable docker
systemctl restart docker

# deploy user: docker access, but its SSH key may only run deploy-ssh.sh
id deploy >/dev/null 2>&1 || useradd -m -s /bin/bash deploy
usermod -aG docker deploy
mkdir -p /opt/convoy/backups
[ -d /opt/convoy/src/.git ] || git clone -q "$REPO" /opt/convoy/src
chown -R deploy:deploy /opt/convoy
chmod 700 /opt/convoy/backups
[ -f /opt/convoy/convoy.env ] && chmod 600 /opt/convoy/convoy.env
if [ -n "${DEPLOY_PUBKEY:-}" ]; then
  install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
  echo "command=\"/opt/convoy/src/deploy/lite/deploy-ssh.sh\",no-port-forwarding,no-X11-forwarding,no-agent-forwarding,no-pty $DEPLOY_PUBKEY" > /home/deploy/.ssh/authorized_keys
  chown deploy:deploy /home/deploy/.ssh/authorized_keys
  chmod 600 /home/deploy/.ssh/authorized_keys
fi

# cron: backup 02:30 IST (21:00 UTC), DuckDNS refresh every 30 min (both read /opt/convoy/convoy.env)
cat > /etc/cron.d/convoy <<'EOF'
SHELL=/bin/bash
0 21 * * * deploy /opt/convoy/src/deploy/lite/backup.sh >> /opt/convoy/backups/backup.log 2>&1
*/30 * * * * deploy set -a; . /opt/convoy/convoy.env; curl -fsS "https://www.duckdns.org/update?domains=${DUCKDNS_DOMAIN}&token=${DUCKDNS_TOKEN}&ip=" >/dev/null
EOF

echo "bootstrap done. Next: /opt/convoy/convoy.env (deploy/lite/convoy.env.example), then the first deploy."
