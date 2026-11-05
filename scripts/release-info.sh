#!/usr/bin/env bash
# Usage: release-info.sh <apk>...   Prints, as Markdown, each APK's SHA-256 and its signing certificate's SHA-256.
# Fails if an APK is not validly signed. Needs apksigner from the Android SDK build-tools: keytool cannot read the
# v2/v3 signatures these APKs carry, so there is no fallback.
set -euo pipefail

apksigner=""
for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /usr/local/lib/android/sdk "$HOME/Android/Sdk"; do
  [ -n "$sdk" ] || continue
  found=$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)
  [ -z "$found" ] || { apksigner=$found; break; }
done
[ -n "$apksigner" ] || apksigner=$(command -v apksigner || true)
[ -n "$apksigner" ] || { echo "apksigner not found (looked in ANDROID_HOME, ANDROID_SDK_ROOT, the runner SDK and PATH)" >&2; exit 1; }

echo "### Checksums and signing certificate"
echo
echo "Check a download with \`sha256sum -c <file>.sha256\`, and compare the certificate with \`apksigner verify --print-certs <apk>\`."
echo
for apk in "$@"; do
  sum=$(sha256sum "$apk" | cut -d' ' -f1)
  out=$("$apksigner" verify --print-certs "$apk" 2>&1 || true)
  cert=$(printf "%s
" "$out" | grep -i "certificate SHA-256 digest" | head -1 | sed "s/.*: *//")
  [ -n "$cert" ] || { echo "could not read the signing certificate of $apk (apksigner: $apksigner)" >&2; printf "%s
" "$out" | head -8 >&2; exit 1; }
  echo "- \`$(basename "$apk")\`"
  echo "  - SHA-256: \`$sum\`"
  echo "  - signing certificate SHA-256: \`$cert\`"
done
