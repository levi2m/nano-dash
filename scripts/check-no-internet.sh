#!/usr/bin/env bash
# Fails if a shipped manifest asks for the INTERNET permission. Run from android/ after assembleRelease:
# it reads the merged manifests of both apps (which include every library's manifest) and, when the Android SDK
# build-tools are present, the permissions inside the built release APKs.
set -euo pipefail

fail=0
for module in app wear; do
  mapfile -t manifests < <(find "$module/build/intermediates/merged_manifests" -name AndroidManifest.xml 2>/dev/null)
  if [ "${#manifests[@]}" -eq 0 ]; then
    echo "no merged manifest for :$module; build it first" >&2
    exit 2
  fi
  for m in "${manifests[@]}"; do
    if grep -q 'android\.permission\.INTERNET' "$m"; then
      echo "INTERNET permission in $m" >&2
      fail=1
    else
      echo "ok: no INTERNET in $m"
    fi
  done
done

aapt2=$(ls -d "${ANDROID_HOME:-/nonexistent}"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true)
if [ -n "$aapt2" ]; then
  for apk in app/build/outputs/apk/release/*.apk wear/build/outputs/apk/release/*.apk; do
    [ -e "$apk" ] || continue
    if "$aapt2" dump permissions "$apk" | grep -q 'android\.permission\.INTERNET'; then
      echo "INTERNET permission in $apk" >&2
      fail=1
    else
      echo "ok: no INTERNET in $apk"
    fi
  done
else
  echo "aapt2 not found: only the merged manifests were checked" >&2
fi
exit $fail
