#!/bin/bash
# Build RAMICS conference presentation PDF and PowerPoint from Markdown

set -e

cd "$(dirname "${BASH_SOURCE[0]}")"

echo "Building RAMICS conference presentation..."

echo "  → PDF..."
npx @marp-team/marp-cli ramics_presentation.md -o ramics_presentation.pdf --timeout 180000

echo "  → PowerPoint..."
npx @marp-team/marp-cli ramics_presentation.md -o ramics_presentation.pptx --timeout 180000

echo "Done!"
echo "Output files:"
ls -lh ramics_presentation.pdf ramics_presentation.pptx
