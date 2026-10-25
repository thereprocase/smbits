# Transfer tuning

The starting point is remembered for a specific combination of:

- Android's current default link transport and reported cellular radio type when accessible.
- VPN presence and a user-supplied route label, such as `X VPN` or `Home Wi-Fi`.
- Signal quality bucket: unknown, weak, fair, good, excellent. An optional manual override handles unavailable device reports.
- Metered/unmetered status.
- Ordered source and destination namespaces, including SMB server, share, port and security policy. Uploads, downloads and different roots therefore have distinct keys. A local selected document tree also participates in the key.
- Copy versus move, and mean file-size bucket: below 256 KiB, 256 KiB to below 8 MiB, or at least 8 MiB.

For example, repeated transfers over the same cellular link, `X VPN`, excellent signal and the same share root use the same starting history, provided direction and workload class also match. Signal bars alone are not treated as a bandwidth measurement. Different manufacturers have different bar scales; the app uses Android's coarse signal level.

## Learning and live feedback

Unknown contexts start at two parallel files. Positive history requires at least three consecutive measured windows at the same concurrency with queued demand, positive throughput and latency, and no observed pressure. Manually rate-limited jobs do not train history. Pauses reset the current measurement session. Windows spanning a reported network change are discarded and the new context is selected. Short transfers and the draining end of a queue cannot teach an excessive worker count.

The learner prefers fewer workers when their measured rate is within 5% of the best rate observed in the current session. Fresh sustained observations can replace old throughput assumptions. Positive updates are persisted only after a fully successful job; detected pressure can reduce an existing prior earlier. After at least three measured demand windows, a previously unknown but noisy context can also retain a conservative backoff value. It does not need to demonstrate high throughput to remember that it struggled. Error backoff is conservative and can include storage errors, since end-to-end responsiveness includes the local provider as well as the link.

Saved starts decay halfway toward two workers every seven days and expire after 30 days. At most 128 context records are retained; oldest records are evicted. Settings provides a history reset. Profile keys are SHA-256 hashes of length-framed context fields. This avoids storing literal root names in the history file, but hashes are not encryption and predictable names can be guessed. History remains in app-private storage with backup disabled; no telemetry is sent. The user-supplied route label is stored locally as a setting.

The live controller responds to throughput changes, sampled I/O p95, browsing latency and errors. Sampled chunk latency excludes intentional rate-cap waits. It backs off under pressure and periodically probes for increased capacity while queued work exists. An extra worker must improve rate by at least 4% across two windows; a latency increase above 50% also needs at least 25% more throughput. Otherwise the probe returns to the previous concurrency, with a cooldown. These thresholds are tuning policy and need physical-device benchmarking. Browsing has a separate executor. Existing file operations finish when admission is reduced; this is not instantaneous cancellation of active workers.

## Worker ceilings

The default ceiling setting is **0 = Auto**. The absolute application guard is 128 workers; users can choose a lower ceiling from 1–128. The current memory estimate is also applied to learned starting values.

The estimate reserves the larger of 32 MiB or one quarter of the maximum Java heap for other work, then budgets 4 MiB for each additional worker. Existing active workers are included because their allocations already appear in used heap. Android's low-memory flag reduces admission to one. The estimate is conservative policy, not proof of memory safety: provider buffers, SMB messages, native stacks, crypto, and device implementations vary. It needs profiling on real devices. The executor grows with admitted concurrency and lets idle threads expire rather than eagerly creating 128 threads.

## Android visibility limits

The app observes the system default network and never binds sockets around the VPN. This is a heuristic for the path used by SMB: split tunnels and LAN routes can differ from the default network. Existing SMB connections can also remain attached to an earlier path until reconnect; a context change is a reason to remeasure, not proof that an existing socket migrated.

Android restricts another application's VPN identity, so distinct VPN routes need a route label. An unlabeled VPN starts conservatively and does not retain history across jobs. A change between two VPN providers that exposes identical network capabilities is not reliably identifiable; change the route label as well. Wi-Fi names are not collected and no location permission is requested, so label different Wi-Fi networks when they should have separate histories.

On Android 13+, the normal `READ_BASIC_PHONE_STATE` permission supplies the reported radio type when available; no phone identifiers, contacts or call data are collected. Older Android versions fall back to generic cellular transport. Signal information can be unavailable or stale. 5G non-standalone service may be reported as LTE by the radio API; a route label can distinguish an intended 5G context. Unknown information stays unknown instead of being inferred from bandwidth estimates.

References: [Android network state](https://developer.android.com/develop/connectivity/network-ops/reading-network-state), [VPN owner visibility](https://developer.android.com/reference/android/net/NetworkCapabilities#getOwnerUid()), [radio type](https://developer.android.com/reference/android/telephony/TelephonyManager#getDataNetworkType()), [signal strength](https://developer.android.com/reference/android/telephony/TelephonyManager#getSignalStrength()).

These policies are unit-tested and exercised in an emulator; real cellular/VPN handovers, NAS capacity, power use and phone thermal behavior require physical-device qualification. No maximum-throughput claim follows from the heuristics alone.
