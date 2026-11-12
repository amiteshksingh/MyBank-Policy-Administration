#!/usr/bin/env bash
set -euo pipefail
OPA_URL="${OPA_URL:-http://localhost:8181}"
POLICY_ADMIN_URL="${POLICY_ADMIN_URL:-http://localhost:9090}"
POLICY_ID="${1:?Usage: verify-opa-storage.sh POLICY_ID}"
echo "Deployment plan:"
curl -fsS "$POLICY_ADMIN_URL/api/deployments/policies/$POLICY_ID/plan" | jq .
echo "Deploying and verifying:"
curl -fsS -X POST "$POLICY_ADMIN_URL/api/deployments/policies/$POLICY_ID" | jq .
echo "Independent verification:"
curl -fsS "$POLICY_ADMIN_URL/api/deployments/policies/$POLICY_ID/verify" | jq .
echo "Runtime hierarchy root:"
curl -fsS "$OPA_URL/v1/data/mybank/runtime" | jq .
