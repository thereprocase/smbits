# Changelog

## 0.1.0-alpha.2 — 2026-10-25

- The app is now called SMBits. The package ID is unchanged, so this build installs over alpha.1 and keeps its saved locations, passwords and tuning history.
- Rebuilt and retested: 36 unit and Samba cases on SMB 3 with encryption, 4 on signed SMB 2.1, and an emulator copy of 9 files (35.9 MiB) to an encrypted share, with every server-side SHA-256 matching the source.

## 0.1.0-alpha.1 — 2026-10-24

First review build.

- Local folders through Android's folder picker, and SMB shares by UNC or `smb://` path, side by side as locations A and B.
- Copy, cut, paste, rename, delete, new folder, sort, filter and filename search.
- Parallel transfers with adaptive concurrency, a memory-based ceiling or a user ceiling of 1–128, and an optional I/O rate cap.
- Tuning history per network type, signal, route label, share, direction and workload size.
- SHA-256 readback of every copied file; moves delete the source only after verification.
- SMB 3 encryption required by default; signed SMB 2/3 by explicit choice; SMB 1 and guest sessions refused.
- Optional password storage with an Android Keystore key.
