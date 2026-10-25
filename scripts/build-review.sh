#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17 installation}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
sign_dir="${SMB_FILES_SIGNING_DIR:-$HOME/.config/smb-files/signing}"
install -d -m 700 "$sign_dir"
export SMB_FILES_KEYSTORE="$sign_dir/review.p12"
export SMB_FILES_KEY_PASSWORD_FILE="$sign_dir/password"
if [[ ! -f "$SMB_FILES_KEYSTORE" ]]; then
    if [[ ! -f "$SMB_FILES_KEY_PASSWORD_FILE" ]]; then
        python3 - "$SMB_FILES_KEY_PASSWORD_FILE" <<'PY'
import os,secrets,sys
fd=os.open(sys.argv[1],os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,'w') as stream: stream.write(secrets.token_urlsafe(32))
PY
    fi
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$SMB_FILES_KEYSTORE" -storetype PKCS12 \
        -storepass:file "$SMB_FILES_KEY_PASSWORD_FILE" -keypass:file "$SMB_FILES_KEY_PASSWORD_FILE" \
        -alias smb-files -keyalg RSA -keysize 3072 -validity 3650 -dname 'CN=SMB Files local review'
    chmod 600 "$SMB_FILES_KEYSTORE"
fi
./gradlew --no-daemon --max-workers=2 :core:test :app:lintRelease :app:assembleRelease "$@"
mkdir -p artifacts
cp app/build/outputs/apk/release/app-release.apk artifacts/smbits-0.1.0-alpha.2.apk
sha256sum artifacts/smbits-0.1.0-alpha.2.apk > artifacts/SHA256SUMS
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs artifacts/smbits-0.1.0-alpha.2.apk
