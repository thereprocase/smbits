#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker_local() {
    env -u DOCKER_HOST -u DOCKER_CONTEXT -u DOCKER_TLS -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH \
        docker --host=unix:///var/run/docker.sock "$@"
}
docker_local info >/dev/null
./gradlew --no-daemon --max-workers=2 :core:testBundle
docker_local build --secret "id=proxy_ca,src=${SMB_FILES_TEST_CA:-/etc/ssl/certs/ca-certificates.crt}" \
    -t smbfiles-test-samba:local scripts/samba
container="smbfiles-test-$$"
cleanup() { docker_local rm -f "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker_local run -d --rm --network none --name "$container" \
    --tmpfs /share:uid=1000,gid=1000,mode=0700 smbfiles-test-samba:local >/dev/null
docker_local cp core/build/test-bundle/. "$container:/tests"
docker_local exec "$container" java -Dsmb.integration=true -cp '/tests/classes:/tests/test-classes:/tests/lib/*' \
    org.junit.runner.JUnitCore dev.gridline.files.core.TransferTest dev.gridline.files.core.TuningTest dev.gridline.files.core.SmbIntegrationTest
cleanup
docker_local run -d --rm --network none --name "$container" \
    --tmpfs /share:uid=1000,gid=1000,mode=0700 smbfiles-test-samba:local sh -c \
    'sed -e "/server min protocol/a server max protocol = SMB2_10" -e "s/smb encrypt = required/smb encrypt = off/" /etc/samba/smb.conf > /tmp/smb2.conf; exec smbd --foreground --no-process-group --debug-stdout -s /tmp/smb2.conf' >/dev/null
docker_local cp core/build/test-bundle/. "$container:/tests"
docker_local exec "$container" java -Dsmb.legacy=true -cp '/tests/classes:/tests/test-classes:/tests/lib/*' \
    org.junit.runner.JUnitCore dev.gridline.files.core.SmbIntegrationTest
