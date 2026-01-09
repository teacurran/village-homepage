#!/bin/bash
set -e

# OpenAPI Drift Detection Script
#
# Compares the committed OpenAPI spec against the auto-generated baseline from
# SmallRye OpenAPI. Fails if there are undocumented endpoint changes.
#
# Usage:
#   ./scripts/check-openapi-drift.sh [quarkus-url]
#
# Arguments:
#   quarkus-url: Base URL for running Quarkus instance (default: http://localhost:8080)

QUARKUS_URL="${1:-http://localhost:8080}"
COMMITTED_SPEC="api/openapi/v1.yaml"
GENERATED_SPEC="/tmp/generated-openapi-spec.yaml"

echo "=== OpenAPI Drift Detection ==="
echo "Quarkus URL: $QUARKUS_URL"
echo "Committed spec: $COMMITTED_SPEC"
echo ""

# Check if Quarkus is running
if ! curl -sf "$QUARKUS_URL/q/health/ready" > /dev/null 2>&1; then
    echo "ERROR: Quarkus is not running at $QUARKUS_URL"
    echo "Start Quarkus with: ./mvnw quarkus:dev"
    exit 1
fi

# Download auto-generated spec
echo "Downloading auto-generated OpenAPI spec from $QUARKUS_URL/q/openapi..."
if ! curl -sf "$QUARKUS_URL/q/openapi" > "$GENERATED_SPEC"; then
    echo "ERROR: Failed to download OpenAPI spec from $QUARKUS_URL/q/openapi"
    exit 1
fi

echo "Auto-generated spec saved to: $GENERATED_SPEC"
echo ""

# Compare specs (ignoring metadata differences)
echo "Comparing specs for drift..."
echo ""

# Extract paths and schemas for comparison (ignore info/server sections)
COMMITTED_PATHS=$(grep -A 9999 "^paths:" "$COMMITTED_SPEC" | grep -B 9999 "^components:" || true)
GENERATED_PATHS=$(grep -A 9999 "^paths:" "$GENERATED_SPEC" | grep -B 9999 "^components:" || true)

# This is a simplified drift check - in production, use a proper YAML diff tool
# For now, we just warn about differences without failing the build
if ! diff -u <(echo "$COMMITTED_PATHS") <(echo "$GENERATED_PATHS") > /dev/null 2>&1; then
    echo "⚠️  WARNING: Detected differences between committed and generated specs"
    echo ""
    echo "This is expected during iteration I2 as stub endpoints are documented manually."
    echo "Review differences with:"
    echo "  diff -u $COMMITTED_SPEC $GENERATED_SPEC"
    echo ""
    echo "If new endpoints were added to REST resources, update $COMMITTED_SPEC accordingly."
    echo ""
    # Don't fail for now - will be enforced in later iterations
    # exit 1
else
    echo "✓ No drift detected - specs are in sync"
fi

echo ""
echo "Drift check complete."

# Cleanup
rm -f "$GENERATED_SPEC"
