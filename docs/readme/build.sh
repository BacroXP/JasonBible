#!/usr/bin/env bash
# Regenerate README.md from the HTML source in this folder.
#
#   pandoc docs/readme/readme.html -f html -t gfm --wrap=none -o README.md
#
# The plain pandoc command keeps GitHub-hostile attributes and the page-local
# icon path, so prefer this script for the real output.
#
# Pandoc rewrites the hero's align="center" as data-align="center"; GitHub
# only honours the classic align attribute, so it is restored afterwards.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

if ! command -v pandoc >/dev/null 2>&1; then
    echo "error: pandoc is not installed (https://pandoc.org/installing.html)" >&2
    exit 1
fi

pandoc docs/readme/readme.html --from html --to gfm --wrap=none --output README.md

# Post-pass for GitHub's GFM rendering:
#  - pandoc maps the hero's align="center" to data-align, which GitHub ignores;
#  - percentage <img> widths become style="width:70.0%" (GitHub strips style),
#    so they are turned back into the classic width attribute;
#  - pandoc writes fenced-code info strings with a leading space ("``` bash"),
#    which some highlighters dislike;
#  - hard line breaks are two trailing spaces in older pandoc and a backslash
#    in newer ones; normalise to the backslash form so rebuilds are stable.
sed -i.bak \
    -e 's/<div\([^>]*\) data-align="center"/<div\1 align="center"/' \
    -e 's/<div class="hero" align="center">/<div align="center">/' \
    -e 's/<div align="center" class="hero">/<div align="center">/' \
    -e 's|src="icon.png"|src="docs/readme/icon.png"|g' \
    -e 's/ style="width:\([0-9.]*\)%"/ width="\1%"/g' \
    -e 's/width="\([0-9]*\)\.0%"/width="\1%"/g' \
    -e 's/^``` \([A-Za-z0-9_+-]*\)$/```\1/' \
    -e 's/  $/\\/' \
    README.md
rm -f README.md.bak

# Pandoc drops HTML comments, so add the "generated file" warning here.
tmp="$(mktemp)"
{
    echo '<!-- Generated from docs/readme/readme.html by docs/readme/build.sh; edit the HTML, not this file. -->'
    echo
    cat README.md
} > "$tmp"
mv "$tmp" README.md

echo "README.md regenerated from docs/readme/readme.html"
