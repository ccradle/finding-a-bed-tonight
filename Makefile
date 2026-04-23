.PHONY: help rehearse-deploy

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
	  awk 'BEGIN {FS = ":.*?## "}; {printf "  %-20s %s\n", $$1, $$2}'

rehearse-deploy: ## Run the operator-laptop prod-mirror rehearsal before tagging a release (~10 min)
	bash scripts/deploy-rehearsal.sh
