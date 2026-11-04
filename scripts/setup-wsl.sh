#!/usr/bin/env bash
# User-space toolchain for nano-dash in WSL. No sudo. Each component is independent,
# re-runnable (already-installed parts are skipped), and checksum-verified where the
# publisher provides a checksum.
set -uo pipefail

OPT="$HOME/.local/opt"
BIN="$HOME/.local/bin"
CFG="$HOME/.config/nano-dash"
mkdir -p "$OPT" "$BIN" "$CFG"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
declare -A RESULT

ok()   { RESULT["$1"]="ok: $2"; echo "[ok]   $1 - $2"; }
fail() { RESULT["$1"]="FAILED: $2"; echo "[FAIL] $1 - $2"; }

# ---------- uv + Python packages ----------
echo "== python"
if ! command -v uv >/dev/null; then pipx install uv >/dev/null 2>&1; fi
export PATH="$BIN:$PATH"
if command -v uv >/dev/null; then
  VENV="$HOME/.venvs/nano-dash"
  [ -x "$VENV/bin/python" ] || uv venv "$VENV" --python python3 >/dev/null 2>&1
  pkgs_ok=""; pkgs_bad=""
  for p in garmin-fit-sdk bleak protobuf grpcio-tools numpy fitparse gpxpy pyosmogps; do
    if uv pip install --python "$VENV/bin/python" "$p" >"$WORK/pip_$p.log" 2>&1; then
      pkgs_ok="$pkgs_ok $p"
    else
      pkgs_bad="$pkgs_bad $p"; tail -3 "$WORK/pip_$p.log"
    fi
  done
  [ -x "$VENV/bin/pyosmogps" ] && ln -sfn "$VENV/bin/pyosmogps" "$BIN/pyosmogps"
  ok python "uv $(uv --version | cut -d' ' -f2), venv $VENV; installed:$pkgs_ok; failed:${pkgs_bad:- none}"
else
  fail python "uv could not be installed"
fi

# ---------- ExifTool ----------
# exiftool.org no longer serves the tarball itself, so it is fetched from SourceForge
# and verified against the SHA-256 that exiftool.org publishes.
echo "== exiftool"
if [ -x "$BIN/exiftool" ] && "$BIN/exiftool" -ver >/dev/null 2>&1; then
  ok exiftool "already installed: $("$BIN/exiftool" -ver)"
else
(
  set -e
  cd "$WORK"
  v="$(curl -fsSL https://exiftool.org/ver.txt | tr -d '[:space:]')"
  t="Image-ExifTool-${v}.tar.gz"
  curl -fsSL https://exiftool.org/checksums.txt -o checksums.txt
  want="$(grep -E "^SHA2-256\(${t}\)" checksums.txt | sed 's/.*= *//')"
  [ -n "$want" ] || { echo "no published checksum for ${t}"; exit 1; }
  curl -fsSL -o "$t" "https://sourceforge.net/projects/exiftool/files/${t}/download"
  got="$(sha256sum "$t" | cut -d' ' -f1)"
  [ "$want" = "$got" ] || { echo "checksum mismatch want=$want got=$got"; exit 1; }
  rm -rf "$OPT/exiftool" "$WORK/exifx"; mkdir "$WORK/exifx"
  tar -xzf "$t" -C "$WORK/exifx"
  mv "$WORK/exifx"/Image-ExifTool-* "$OPT/exiftool"
  ln -sfn "$OPT/exiftool/exiftool" "$BIN/exiftool"
) && ok exiftool "$("$BIN/exiftool" -ver 2>&1)" || fail exiftool "see output above"
fi

# ---------- JDK 21 (Temurin) ----------
echo "== jdk"
if [ -x "$OPT/jdk-21/bin/java" ]; then
  ok jdk "already installed: $("$OPT/jdk-21/bin/java" -version 2>&1 | head -1)"
else
(
  set -e
  cd "$WORK"
  curl -fsSL "https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse" -o jdk.json
  read -r link sum name < <(python3 -I - <<'PY'
import json
d = json.load(open("jdk.json"))[0]["binary"]["package"]
print(d["link"], d["checksum"], d["name"])
PY
)
  curl -fsSL "$link" -o "$name"
  echo "${sum}  ${name}" | sha256sum -c -
  rm -rf "$OPT/jdk-21" "$WORK/jdkx"; mkdir "$WORK/jdkx"
  tar -xzf "$name" -C "$WORK/jdkx"
  mv "$WORK/jdkx"/jdk-* "$OPT/jdk-21"
) && ok jdk "$("$OPT/jdk-21/bin/java" -version 2>&1 | head -1)" || fail jdk "see output above"
fi

# ---------- JADX headless MCP jar for REA ----------
echo "== rea jadx jar"
(
  set -e
  d="$HOME/.local/share/rea/android"; mkdir -p "$d"; cd "$d"
  want='6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75'
  if ! echo "${want}  jadx-headless-mcp-0.7.1-all.jar" | sha256sum -c - >/dev/null 2>&1; then
    tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
    curl -fL -o "$tmp" https://github.com/1013503897/jadx-headless-mcp/releases/download/v0.7.1/jadx-headless-mcp-0.7.1-all.jar
    echo "${want}  $tmp" | sha256sum -c -   # verified before it gets its final name
    mv "$tmp" jadx-headless-mcp-0.7.1-all.jar
  fi
) && ok jadx-jar "$HOME/.local/share/rea/android/jadx-headless-mcp-0.7.1-all.jar" || fail jadx-jar "see output above"

# ---------- unzip shim (Flutter needs an `unzip` binary; installing the package needs sudo) ----------
echo "== unzip shim"
if command -v unzip >/dev/null 2>&1; then
  ok unzip "already present: $(command -v unzip)"
else
  cat > "$BIN/unzip" <<'PYEOF'
#!/usr/bin/env python3
"""Minimal user-space unzip: supports `unzip [-o] [-q] ARCHIVE [-d DIR]`, restores file modes."""
import os, stat, sys, zipfile

args, dest, archive, i = sys.argv[1:], ".", None, 0
while i < len(args):
    a = args[i]
    if a == "-d":
        dest = args[i + 1]; i += 2; continue
    if a.startswith("-"):
        i += 1; continue
    if archive is None:
        archive = a
    i += 1
if archive is None:
    sys.exit("usage: unzip [-o] [-q] ARCHIVE [-d DIR]")

root = os.path.realpath(dest)
os.makedirs(root, exist_ok=True)
with zipfile.ZipFile(archive) as z:
    for info in z.infolist():
        target = os.path.realpath(os.path.join(root, info.filename))
        if target != root and not target.startswith(root + os.sep):
            sys.exit(f"unsafe path in archive: {info.filename}")
        z.extract(info, root)
        mode = info.external_attr >> 16
        if mode and not info.is_dir():
            os.chmod(target, stat.S_IMODE(mode))
PYEOF
  chmod +x "$BIN/unzip"
  ok unzip "shim installed at $BIN/unzip"
fi

# ---------- Flutter (stable, shallow clone) ----------
echo "== flutter"
(
  set -e
  if [ ! -d "$OPT/flutter/.git" ]; then
    git clone --depth 1 -b stable https://github.com/flutter/flutter.git "$OPT/flutter"
  fi
  export PATH="$BIN:$OPT/flutter/bin:$PATH"
  export FLUTTER_SUPPRESS_ANALYTICS=true CI=true
  flutter config --no-analytics >/dev/null 2>&1 || true
  dart --disable-analytics >/dev/null 2>&1 || true
  flutter --version
) && ok flutter "$("$OPT/flutter/bin/flutter" --version 2>/dev/null | head -1)" || fail flutter "see output above"

# ---------- environment file ----------
cat > "$CFG/env.sh" <<EOF
# nano-dash toolchain (managed by the project setup)
export JAVA_HOME="$OPT/jdk-21"
export REA_JADX_MCP_JAR="$HOME/.local/share/rea/android/jadx-headless-mcp-0.7.1-all.jar"
export PATH="\$JAVA_HOME/bin:$OPT/flutter/bin:\$HOME/.local/bin:\$PATH"
export FLUTTER_SUPPRESS_ANALYTICS=true
EOF
if ! grep -q 'nano-dash/env.sh' "$HOME/.profile" 2>/dev/null; then
  printf '\n# nano-dash toolchain\n[ -f "$HOME/.config/nano-dash/env.sh" ] && . "$HOME/.config/nano-dash/env.sh"\n' >> "$HOME/.profile"
fi

echo
echo "===== SUMMARY ====="
for k in python exiftool jdk jadx-jar unzip flutter; do echo "$k: ${RESULT[$k]:-not run}"; done
