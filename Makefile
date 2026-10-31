# Full AWS profile (P6.E), run from this laptop with your `aws login` session (profile convoy-aws, 12 h).
# No GitHub runner: the organisation SCP denies the GitHub OIDC provider, and no stored keys are allowed.
#   make full-up RUN=r1 IMAGES=path/to/images.env      make full-down RUN=r1      make full-verify RUN=r1
# The scheduled destroy (maximum duration) is pending a decision: docs/runbooks/full-profile.md.
export AWS_PROFILE ?= convoy-aws
export AWS_REGION ?= ap-southeast-2

full-up:
	scripts/full.sh up $(RUN) $(IMAGES)

full-down:
	scripts/full.sh down $(RUN)

full-verify:
	scripts/full.sh verify $(RUN)

.PHONY: full-up full-down full-verify
