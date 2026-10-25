# Security and file-operation behavior

## Trust boundaries

The app runs as an ordinary Android application. Its activity is the only exported component. The transfer service is not exported. There are no analytics, ads, WebViews, remote update mechanisms, background discovery, or external command execution.

Local access uses persisted Android document-tree grants. SMB paths and filenames are validated independently of the UI. SMB operations reject reparse traversal, disable DFS referral following, and require authenticated sessions. There is no blanket promise to connect to every UNC share: reachability, permissions, server dialect, and the chosen security policy must allow it.

SMBJ handles protocol negotiation and authentication. The default policy allows SMB 3.0, 3.0.2, and 3.1.1 with required encryption and signing policy. Compatibility mode additionally allows SMB 2.0.2/2.1, still requiring signing. A failed secure connection is not retried at a lower security setting. A closed connection is re-established before a subsequent operation with the same server, credentials, and policy. A failed in-flight write, rename, or delete is not automatically replayed. Guest and anonymous sessions are rejected after authentication. TCP connection timeout is SMBJ's five-second default; operation timeout is 15 seconds. The background packet reader has no idle socket deadline, so a healthy idle session stays open. Some blocking provider or network calls can delay cancellation. DNS resolution can be subject to platform-specific delays.

SMB authentication is not certificate pinning. Users must select trusted servers and understand domain/NTLM trust. A malicious server or a compromised Android process remains outside the protection offered by transfer hashes. The client currently uses SMBJ's Android-compatible NTLM authentication; a managed Kerberos setup is not implemented.

## Credentials and local state

Passwords are persisted only when the user opts in. AES-256-GCM uses a non-exportable Android Keystore key, fresh cipher-generated IVs, and the profile ID as associated data. Keystore access requires an unlocked device. Password fields disable autofill collection. The live connection retains a password character array in process memory to reconnect after network/background disconnects; explicit disconnect clears it. Per-authentication copies are cleared. Java strings and SMBJ internal copies cannot be guaranteed to disappear immediately from process memory.

Connection metadata (host/share, username, domain, security preference, port, bookmark name) is stored in private app preferences. Cloud backup and device-to-device data extraction are excluded. Password material is never placed in intents, notifications, paths, or logs. SLF4J is bound to the no-op implementation. A transfer notification contains counts and state, not server paths or credentials.

The app retains job details in process memory only. A private interrupted-session flag is persisted. Forgetting a location removes its saved secret; disconnecting idle sessions closes live SMB sessions. Android's own screenshots, device logs, backups made by privileged tools, and memory inspection by a compromised device are not covered by these controls.

## Copy, replace, and move

1. Validate selection and prevent overlapping selected folders/descendants and identifiable self/nested copies.
2. Enumerate to a bounded plan (100,000 items, depth 128). Empty destination folders can be created during planning.
3. Create an exclusive random `.smbfiles-<uuid>.partial` file alongside the destination.
4. Stream the source into the temporary file while computing SHA-256. Close/flush and reject changed source size/mtime.
5. Re-read the destination temporary file and compare SHA-256.
6. Recheck conflict policy. Publish by rename. SMB rename uses `replaceIfExist=false`.
7. For explicit replacement, first rename the previous destination to a random `.backup` name, publish the new file, then delete the backup. If publication fails, attempt to restore the backup; report its location if restoration fails.
8. For moves, hash the source again and recheck metadata before deleting that source. Remove source directories only when empty and the job has no skipped files or errors.

Cancel/pause are cooperative between operations and chunks. A synchronous provider operation can finish before cancellation is observed. Terminal status is deferred until worker completion. Cancellation does not undo files already completed, and confirmed source deletions from completed move items are not rolled back.

Temporary cleanup failures and retained replacement backups are reported in the job's errors. After process death or loss of power, users must inspect `.partial` and `.backup` files; there is no automatic cleanup that could delete recoverable data.

## Important accuracy limits

Document-provider rename is not universally atomic, durable, or exclusive. The local provider checks existing names and verifies returned names, but Android's generic provider API cannot provide a cross-process transactional rename guarantee. SMB protects against accidental destination clobber with an exclusive rename, but replacement's backup/publish sequence is not a single atomic transaction.

Concurrent external writers can modify a source after the final validation and before deletion. SMB read handles deny simultaneous writers while they are open; the full move is not a server-side transaction or snapshot. Avoid moving actively edited data until an exclusive-handle/snapshot policy is implemented and qualified.

The app cannot identify all aliases for the same underlying share or document (different server aliases, overlapping provider roots, hard links). It rejects known identical/descendant paths but cannot prove identity across arbitrary providers. A provider can also misreport size/time or link type. Unknown file sizes fail verification rather than producing a success claim.

No content hash proves that remote storage reached durable media. SMB flush requests and Android stream closure are subject to the server/provider's guarantees. Metadata preservation, ACL copying, and archive/security-descriptor semantics are not implemented.

Tuning history stays in app-private storage with backups disabled. It stores hashed route/root context keys and numeric performance priors, never passwords. Hashes are not encryption. The optional route label is a local setting. Basic radio-state access on Android 13+ supplies link classification; the app does not collect device identifiers, contacts, call logs, or Wi-Fi names. See [tuning.md](tuning.md) for VPN identity and default-route limitations.
