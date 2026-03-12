#!/bin/bash
# Build presentation PDF and PowerPoint from Markdown

set -e

cd "$(dirname "${BASH_SOURCE[0]}")"

echo "Building presentation..."

echo "  → PDF..."
npx @marp-team/marp-cli presentation.md -o presentation.pdf --timeout 180000

echo "  → PowerPoint..."
npx @marp-team/marp-cli presentation.md -o presentation.pptx --timeout 180000

echo "Done!"
echo "Output files:"
ls -lh presentation.pdf presentation.pptx

rm -f basis.pdf

cp presentation.pdf basis.pdf