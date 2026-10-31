# Full AWS profile (P6.E), always through GitHub Actions, so the scheduled destroy holds even with this laptop off.
#   make full-up HOURS=1      make full-down      make full-status
HOURS ?= 1

full-up:
	gh workflow run full.yml -f action=up -f max_hours=$(HOURS)
	@echo "dispatched; watch: gh run watch \$$(gh run list -w full -L 1 --json databaseId -q '.[0].databaseId')"

full-down:
	gh workflow run full.yml -f action=down

full-status:
	gh run list -w full -L 5

.PHONY: full-up full-down full-status
