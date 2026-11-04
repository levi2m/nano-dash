#!/usr/bin/env bash
# Usage: release-info.sh <apk>...   Prints, as Markdown, each APK's SHA-256 and its signing certificate's SHA-256.
# Fails if an APK is not validly signed. Uses apksigner from the Android SDK build-tools, or keytool if that is missing.
set -euo pipefail

apksigner=$(ls -d "${ANDROID_HOME:-/nonexistent}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)

echo "### Checksums and signing certificate"
echo
echo "Check a download with \`sha256sum -c <file>.sha256\`, and compare the certificate with \`apksigner verify --print-certs <apk>\`."
echo
for apk in "$@"; do
  sum=$(sha256sum "$apk" | cut -d' ' -f1)
  if [ -n "$apksigner" ]; then
    cert=$("$apksigner" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
  else
    cert=$(keytool -printcert -jarfile "$apk" | sed -n 's/^[[:space:]]*SHA256: //p' | head -1 | tr -d ':' | tr 'A-F' 'a-f')
  fi
  [ -n "$cert" ] || { echo "could not read the signing certificate of $apk" >&2; exit 1; }
  echo "- \`$(basename "$apk")\`"
  echo "  - SHA-256: \`$sum\`"
  echo "  - signing certificate SHA-256: \`$cert\`"
done
