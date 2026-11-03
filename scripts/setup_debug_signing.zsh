#!/usr/bin/env zsh
# One-time local keystore creation + GitHub Actions secret provisioning.
# Never generates a key on GitHub and never prints/commits signing passwords.
emulate -L zsh
setopt ERR_EXIT PIPE_FAIL NO_UNSET
umask 077

REPO='Mikeyphw/MMRL-LSPosed'
PKG='io.github.mikeyphw.mmrl.lsposed.debug'
ALIAS='mmrl_lsposed_debug'
SCRIPT_DIR="${0:A:h}"
ROOT="${SCRIPT_DIR:h}"
PRIVATE_DIR="$HOME/.config/mmrl-lsposed/signing"
KEYSTORE="$PRIVATE_DIR/debug-signing.p12"
PASSFILE="$PRIVATE_DIR/debug-signing.password"
POLICY="$ROOT/config/debug-signing.json"

if [[ ! -f "$ROOT/.github/workflows/s01-hosted-devtool.yml" ]]; then
    print -u2 'ERROR: Run this script from the applied S01 signing overlay.'
    exit 2
fi
for c in git gh python3 keytool openssl base64 tr awk; do
    if ! command -v "$c" >/dev/null 2>&1; then
        print -u2 "ERROR: Missing $c (on Termux, Java is available via pkg install openjdk-21)."
        exit 2
    fi
done
cd "$ROOT"
if [[ "$(git rev-parse --show-toplevel)" != "$ROOT" ]]; then
    print -u2 'ERROR: Unexpected Git worktree root.'
    exit 2
fi
ORIGIN="$(git remote get-url origin)"
if [[ "$ORIGIN" != "https://github.com/$REPO.git" && "$ORIGIN" != "https://github.com/$REPO" && "$ORIGIN" != "git@github.com:$REPO.git" ]]; then
    print -u2 "ERROR: Repository origin differs from $REPO"
    exit 2
fi
gh auth status >/dev/null
if [[ -L "$PRIVATE_DIR" || -L "$KEYSTORE" || -L "$PASSFILE" || -L "$ROOT/config" || -L "$POLICY" ]]; then
    print -u2 'ERROR: Symlink in signing/private path.'
    exit 2
fi
mkdir -p "$PRIVATE_DIR" "$ROOT/config"
chmod 700 "$PRIVATE_DIR"
if [[ ! -e "$KEYSTORE" && ! -e "$PASSFILE" ]]; then
    print 'Generating a persistent debug-only identity locally (not on GitHub).'
    PW="$(openssl rand -hex 32)"
    export MMRL_DEBUG_LOCAL_STORE_PASS="$PW"
    TEMPKEY="$PRIVATE_DIR/.debug-signing-$PPID-$$.p12"
    if [[ -e "$TEMPKEY" ]]; then
        print -u2 'ERROR: Temporary keystore already exists.'
        exit 2
    fi
    keytool -genkeypair -noprompt \
        -keystore "$TEMPKEY" -storetype PKCS12 \
        -alias "$ALIAS" -storepass:env MMRL_DEBUG_LOCAL_STORE_PASS \
        -keypass:env MMRL_DEBUG_LOCAL_STORE_PASS \
        -dname 'CN=MMRL LSPosed Debug,O=Personal Android Development' \
        -keyalg RSA -keysize 4096 -validity 10950
    chmod 600 "$TEMPKEY"
    mv -- "$TEMPKEY" "$KEYSTORE"
    print -rn -- "$PW" > "$PASSFILE"
    chmod 600 "$PASSFILE"
    unset PW
elif [[ ! -f "$KEYSTORE" || ! -f "$PASSFILE" ]]; then
    print -u2 'ERROR: Partial existing signing identity; refusing to replace or rotate it.'
    exit 2
else
    print 'Reusing existing persistent signing identity.'
fi
if [[ -z "${MMRL_DEBUG_LOCAL_STORE_PASS:-}" ]]; then
    export MMRL_DEBUG_LOCAL_STORE_PASS="$(<"$PASSFILE")"
fi
if [[ -z "$MMRL_DEBUG_LOCAL_STORE_PASS" ]]; then
    print -u2 'ERROR: Empty local signing password.'
    exit 2
fi
# keytool supports -storepass:env; no secret value appears in argv or Git logs.
KEYINFO="$(LC_ALL=C keytool -list -v -keystore "$KEYSTORE" -storetype PKCS12 \
    -alias "$ALIAS" -storepass:env MMRL_DEBUG_LOCAL_STORE_PASS)"
CERT="$(print -r -- "$KEYINFO" | awk '/SHA256:/{gsub(":", "", $2); print tolower($2); exit}')"
unset KEYINFO
if ! python3 - "$CERT" <<'PYCERT'
import re,sys
sys.exit(0 if re.fullmatch('[0-9a-f]{64}', sys.argv[1]) else 1)
PYCERT
then
    print -u2 'ERROR: Cannot inspect the expected certificate SHA-256.'
    exit 2
fi
# Existing trust policy is an immutable identity declaration. Never silently rotate.
if [[ -e "$POLICY" ]]; then
    python3 - "$POLICY" "$CERT" <<'PY'
import json, pathlib, sys
p = pathlib.Path(sys.argv[1]); d = json.loads(p.read_text())
if d.get('certificate_sha256') != sys.argv[2]:
    raise SystemExit('ERROR: Existing policy pins another signer; refusing rotation')
PY
else
    python3 - "$POLICY" "$CERT" <<'PY'
import json, pathlib, sys
p = pathlib.Path(sys.argv[1])
d = {'schema':'mmrl-lsposed.debug-signing.v1',
     'repository':'Mikeyphw/MMRL-LSPosed',
     'application_id':'io.github.mikeyphw.mmrl.lsposed.debug',
     'ref':'refs/heads/main',
     'alias':'mmrl_lsposed_debug',
     'purpose':'debug-only',
     'certificate_sha256':sys.argv[2]}
with p.open('x') as stream:
    json.dump(d, stream, indent=2, sort_keys=True)
    stream.write('\n')
PY
fi
print "Certificate SHA-256 (public identity): $CERT"
print 'Encrypting signing material as GitHub Actions secrets...'
# 'gh secret set' reads stdin and encrypts locally. Values never appear as arguments.
base64 < "$KEYSTORE" | tr -d '\n' | gh secret set MMRL_DEBUG_KEYSTORE_B64 -R "$REPO"
print -rn -- "$MMRL_DEBUG_LOCAL_STORE_PASS" | gh secret set MMRL_DEBUG_STORE_PASSWORD -R "$REPO"
unset MMRL_DEBUG_LOCAL_STORE_PASS CERT
print '\nConfigured GitHub Actions secrets (names only):'
gh secret list -R "$REPO" | grep -E '^MMRL_DEBUG_(KEYSTORE_B64|STORE_PASSWORD)[[:space:]]' || true
print '\nSUCCESS: Debug signing identity is ready. Back up the private .p12 and password securely.'
print "Public policy: $POLICY"
print 'Commit only that policy JSON with the reviewed source overlay. Never commit private keys or password files.'
