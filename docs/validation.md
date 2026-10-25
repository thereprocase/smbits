# Validation

## 0.1.0-alpha.2 — 2026-10-25

This build changes the app's displayed name and version only. It was rebuilt and checked again:

- Release APK built, Android lint passed, signature verified with the same review key as alpha.1.
- `scripts/test-smb.sh`: 36 cases pass on the encrypted SMB 3 server and 4 on the signed SMB 2.1 server, the same counts as alpha.1.
- Android 16 emulator: the signed APK installed, opened a local folder through the system picker, and connected to an encrypted Samba share. A copy of 9 files (35.9 MiB) to the share completed with all files verified, and SHA-256 sums computed independently on the server matched every source file. A second copy reused the saved tuning profile, and a copy under a 1 MiB/s cap held about 1020 KiB/s. The README screenshots come from this run.

## 0.1.0-alpha.1 — 2026-10-24

This is an initial alpha review, not a general production qualification. Only the environments and cases listed here were exercised.

### Build and dependency checks

- JDK 17; Android compile/target SDK 36; minimum SDK 28.
- Release APK builds and Android lint passes with no errors. Remaining lint notices are primarily English-only UI text and newer available build-tool versions.
- APK is signed with a dedicated local RSA-3072 review key and is non-debuggable. Android APK signature verification passes.
- Runtime dependency versions and artifact SHA-256 values are pinned. OSV returned no listed advisories for the six exact runtime artifacts. Seven license texts are included in the APK.
- Source whitespace checks pass. Signing material remains outside the repository and source archive.

### JVM and Samba checks

Thirty-two unit tests pass. They cover recursive copies, empty folders, corrupted destination content, failed writes, verified moves, conflict preservation, skip/keep-both/replace policies, replacement rollback, concurrent destination creation, cancellation, pause/resume, self/nested/overlapping selection refusal, link refusal, source mutation, adaptive latency backoff, UNC/path validation, memory ceilings, stable context keys, learned starts and expiry, sustained-demand learning, failure backoff, workload classes, and acceptance/rollback of concurrency probes.

As a negative control, destination hash verification was deliberately disabled in a temporary copy of `Transfer.java`. Two tests then failed: corrupt destination source retention and preservation of an existing destination before verified replacement. The production source was unchanged. The captured result is `docs/evidence/0.1.0-alpha.1/verification-mutation.txt`.

Four integration cases pass against disposable Samba 4.21.9 containers:

1. SMB 3 encrypted round trip: twelve files of 1 MiB + 123 bytes, parallel upload, remote rename, verified download/move, and removal of empty remote folders. The session is left idle for 22 seconds and then forcibly disconnected between operations; reconnect preserves the encrypted policy and subsequent operations succeed.
2. Incorrect password is rejected.
3. Default encrypted mode refuses a server restricted to SMB 2.1, without downgrading.
4. Explicit signed compatibility mode writes and reads that SMB 2.1 server successfully.

`scripts/test-smb.sh` reproduces these checks. The containers have no external network while tests run. JUnit reports 36 cases in the encrypted run (32 unit + 2 active integration + 2 skipped compatibility cases), and 4 in the compatibility run (2 active + 2 skipped encrypted cases): **36 distinct executed cases** in total.

### Android AVD checks

Environment: Android 16/API 36, x86_64 AVD, KVM acceleration, 1080 × 2400 display override at 420 dpi. The existing validation AVD was launched read-only without snapshot saving. All file operations used disposable test folders and a Samba share exposed only on host loopback.

Observed successful cases:

- Installation and launch of the signed release APK; native UI rendered with IBM Plex fonts.
- Android document-tree picker and explicit folder permission grants; nested local browsing and Up navigation.
- Saved folder access and opt-in Keystore password retrieval survive APK updates. The saved SMB connection reconnects without retyping its password.
- Encrypted local-to-SMB upload of a nested text file, another text file, and an 8 MiB binary file. All three files passed app readback verification and independent SHA-256 checks on the server.
- SMB-to-local download of the same three files, with independent hashes matching the originals.
- Local rename, then a cross-provider Cut/Paste move. The verified remote file matched the original hash and the local source was removed.
- Remote folder creation, keep-both conflict handling, confirmed deletion, and folder filtering.
- A paused move remained a foreground data-sync service while the activity was backgrounded. Cancel retained the local source and removed the remote partial file; no final destination file was published.
- A separate copy paused at 512 KiB, resumed, continued with the activity in the background, and completed with destination verification.
- Light and dark layouts, two independent browsing locations, multi-selection, and transfer details.
- Auto concurrency with a named route profile: an encrypted 32-file, 256 MiB batch completed, and every remote file independently matched the source SHA-256. The UI displayed detected Wi-Fi/excellent signal, adaptive admission, memory ceiling, and chunk latency.
- A subsequent 32 MiB batch trained a context. After force-stopping and reopening the app, a 16 MiB transfer displayed **saved profile · start 2**, completed both files, and preserved the existing destinations using keep-both.

The UI automation helper sometimes could not obtain an idle snapshot while progress changed, and an earlier overlapping helper invocation crashed its own UiAutomationService. Those diagnostics are retained separately in `docs/evidence/0.1.0-alpha.1/ui-automation-diagnostics.txt`.

Fixture checksums, UI dumps and test reports from this run are in [docs/evidence/0.1.0-alpha.1](evidence/0.1.0-alpha.1/).

AVD testing found and led to fixes for an idle socket deadline, recovery from closed SMB sessions between user actions, excessive transfer-view reconstruction, folder-row checkbox focus, stale paused rate display, and dark-mode status contrast. Different authenticated/security-policy sessions now have independent UI identities, while filesystem identity checks use a separate share namespace.

## Remaining qualification

Not yet measured: physical phones, vendor-specific background limits, Android 9–15 runtime behavior, real Wi-Fi/NAS throughput against fixed concurrency or robocopy, battery/thermal effects, long disconnects and six-hour service limits, very large trees, low disk space on real Android providers, or crash/power-loss recovery. Generic document-provider rename/durability and concurrent external writers have the limits described in [security.md](security.md).

The adaptive controller and persistent context history are implemented and tested for their feedback decisions. Radio/VPN identity and route visibility limitations are recorded in [tuning.md](tuning.md). A claim of maximum possible link performance would require those real-device benchmarks.
