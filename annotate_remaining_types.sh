#!/bin/bash

# Script to add @Schema annotations to remaining Type files
# This script processes files that don't already have @Schema annotations

TYPES_DIR="/Users/tea/dev/VillageCompute/code/village-homepage/src/main/java/villagecompute/homepage/api/types"

cd "$TYPES_DIR" || exit 1

# Count files
total_files=$(ls -1 *.java | wc -l | tr -d ' ')
annotated_files=$(grep -l "@Schema" *.java 2>/dev/null | wc -l | tr -d ' ')
remaining=$((total_files - annotated_files))

echo "Total Type files: $total_files"
echo "Already annotated: $annotated_files"
echo "Remaining to annotate: $remaining"
echo ""

# List files that need annotations
echo "Files needing @Schema annotations:"
for file in *.java; do
    if ! grep -q "@Schema" "$file"; then
        echo "  - $file"
    fi
done
