# Lite stack memory on the laptop (P6.A)

**Laptop numbers, not VM numbers** (Windows 11, Docker Desktop on WSL2, 20 logical CPUs, amd64 images). The VM (arm64,
1 OCPU, 6 GB) is measured separately in P6.D.

Run: `JOURNEY=0 scripts/lite-measure.sh` (2026-10-31, raw data in `lite-measure-20261031T114124Z/`): `deploy/lite/compose.yml`
plus `measure.yml` (LocalStack + Mailpit, local profile, for this one run only).

- **Cold start** (`up -d` from empty volumes to every container healthy, including Flyway migrations): **56 s**.
- **Idle** (2 min after healthy):

| container | idle MiB | limit | CPU |
|---|---:|---|---:|
| gateway | 234 | 384MiB | 0.14% |
| realtime | 229 | 384MiB | 1.22% |
| identity | 203 | 384MiB | 0.76% |
| order | 195 | 384MiB | 1.91% |
| notification | 191 | 384MiB | 2.48% |
| audit | 187 | 384MiB | 3.46% |
| dealer | 186 | 384MiB | 1.52% |
| payment | 183 | 384MiB | 1.68% |
| pool | 183 | 384MiB | 1.70% |
| quote | 182 | 384MiB | 1.44% |
| catalog | 175 | 384MiB | 0.87% |
| postgres | 142 | 512MiB | 7.18% |
| web | 52 | 256MiB | 0.00% |
| caddy | 12 | 64MiB | 0.00% |
| razorpay-fake | 12 | 64MiB | 0.00% |
| redis | 9 | 80MiB | 0.17% |
| **total (lite containers)** | **2377** | | |

LocalStack (258 MiB) and Mailpit (21 MiB) are excluded: they don't run on the host.

- **Journey peak: not measured on the laptop.** About 3 GB was free, too little for the stack plus LocalStack and the
  browser, so the peak is measured on the VM in P6.D (labelled as VM numbers). The memory limits (sum ≈ 5.0 GiB) cap the worst case.
- **Verdict:** 2.3 GiB idle is well under the ~5 GB line, so the 6 GB VM goes ahead. JVMs sit at about half their limit,
  which leaves room for the journey peak.
- **Oracle idle reclamation:** an A1 VM is reclaimable only if CPU, network *and* memory all stay under 20 % for 7 days.
  Idle memory is ≈ 39 % of 6 GB, so the memory criterion is not met and the VM is not idle by Oracle's rule; no load is generated.
