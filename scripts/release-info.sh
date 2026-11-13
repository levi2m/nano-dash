#!/usr/bin/env bash
# Usage: release-info.sh <apk>...   Prints, as Markdown, each APK's SHA-256 and its signing certificate's SHA-256.
# Fails if an APK is not validly signed, or if its signing block carries Google Play's "Dependency metadata", which
# F-Droid's scanner rejects (our F-Droid build is reproducible, so the release itself must pass). Needs apksigner from
# the Android SDK build-tools: keytool cannot read the v2/v3 signatures these APKs carry, so there is no fallback.
set -euo pipefail

apksigner=""
for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /usr/local/lib/android/sdk "$HOME/Android/Sdk"; do
  [ -n "$sdk" ] || continue
  found=$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)
  [ -z "$found" ] || { apksigner=$found; break; }
done
[ -n "$apksigner" ] || apksigner=$(command -v apksigner || true)
[ -n "$apksigner" ] || { echo "apksigner not found (looked in ANDROID_HOME, ANDROID_SDK_ROOT, the runner SDK and PATH)" >&2; exit 1; }

# Prints the IDs in an APK's signing block (the "APK Sig Block 42" just before the zip's central directory).
signing_block_ids() {
  python3 -I - "$1" <<'EOF'
import struct, sys
d = open(sys.argv[1], "rb").read()
eocd = d.rfind(b"PK\x05\x06")
cd = struct.unpack_from("<I", d, eocd + 16)[0]
if d[cd - 16:cd] != b"APK Sig Block 42":
    sys.exit("no APK signing block")
pos = cd - struct.unpack_from("<Q", d, cd - 24)[0] - 8 + 8
while pos < cd - 24:
    size = struct.unpack_from("<Q", d, pos)[0]
    print(hex(struct.unpack_from("<I", d, pos + 8)[0]))
    pos += 8 + size
EOF
}

echo "### Checksums and signing certificate"
echo
echo "Check a download with \`sha256sum -c <file>.sha256\`, and compare the certificate with \`apksigner verify --print-certs <apk>\`."
echo
for apk in "$@"; do
  sum=$(sha256sum "$apk" | cut -d' ' -f1)
  out=$("$apksigner" verify --print-certs "$apk" 2>&1 || true)
  cert=$(printf '%s\n' "$out" | grep -i "certificate SHA-256 digest" | head -1 | sed "s/.*: *//")
  [ -n "$cert" ] || { echo "could not read the signing certificate of $apk (apksigner: $apksigner)" >&2; printf '%s\n' "$out" | head -8 >&2; exit 1; }
  if signing_block_ids "$apk" | grep -qx 0x504b4453; then
    echo "$apk carries Google Play's Dependency metadata block; F-Droid rejects it (set dependenciesInfo off in its build file)" >&2
    exit 1
  fi
  echo "- \`$(basename "$apk")\`"
  echo "  - SHA-256: \`$sum\`"
  echo "  - signing certificate SHA-256: \`$cert\`"
done
