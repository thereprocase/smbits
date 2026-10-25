# Dependency review — 2026-10-24

The application code is MIT licensed. Native Android framework views and APIs are used without a separate UI framework dependency. Runtime dependencies resolve only from Maven Central; Android build tooling resolves from Google's repository and Maven Central.

| Runtime artifact | Version | License | Purpose |
|---|---|---|---|
| com.hierynomus:smbj | 0.15.0 | Apache-2.0 | SMB transport, authentication, signing, encryption, file operations |
| com.hierynomus:asn-one | 0.6.0 | Apache-2.0 | ASN.1 structures used by SMBJ authentication |
| org.bouncycastle:bcprov-jdk18on | 1.86 | MIT | Cryptographic primitives used by SMBJ |
| net.engio:mbassador | 1.3.2 | MIT | SMBJ internal event dispatch |
| org.slf4j:slf4j-api | 2.0.18 | MIT | SMBJ logging interface |
| org.slf4j:slf4j-nop | 2.0.18 | MIT | Suppresses runtime logging |

IBM Plex Sans and Mono are bundled under SIL OFL-1.1. Their license text ships inside the app.

SMBJ and Bouncy Castle are established projects, but this selection is not a certification that they are defect-free. SMBJ's small transitive dependency set includes MBassador, whose older release still warrants scrutiny despite no listed advisory in the check below. The app does not implement its own SMB protocol or cryptographic algorithms.

Bouncy Castle 1.86 is pinned above SMBJ's declared 1.85.2 dependency because upstream describes 1.86 as a hardening/security release. The encrypted Samba integration tests exercise this combination.

OSV's Maven query API returned no listed vulnerabilities for these six exact versions on 2026-10-24. The raw result is in [dependency-audit.json](dependency-audit.json). An empty result does not prove security or complete database coverage.

Gradle dependency lockfiles pin the resolved graph. `gradle/verification-metadata.xml` records SHA-256 values for dependency artifacts and metadata; this prevents subsequent unexpected artifact substitution but is not independent authentication of the initial download. The Gradle distribution has a pinned upstream SHA-256. Dependencies and bundled notices must be reviewed together on upgrades.

Development-only tools are not shipped in the APK: JUnit 4 (EPL-1.0), Hamcrest (BSD), Gradle/Android build tools, and the isolated Samba/JDK test container (their respective upstream licenses). Test dependencies are separate from runtime dependencies. The APK contains only the runtime libraries and fonts listed above.

Primary references:

- [SMBJ source and license headers](https://github.com/hierynomus/smbj)
- [SMBJ 0.15.0 Maven publication](https://repo.maven.apache.org/maven2/com/hierynomus/smbj/0.15.0/smbj-0.15.0.pom)
- [Bouncy Castle releases](https://www.bouncycastle.org/download/bouncy-castle-java/)
- [Bouncy Castle license](https://github.com/bcgit/bc-java/blob/r1rv86/LICENSE.html)
- [MBassador license](https://github.com/bennidi/mbassador/blob/master/LICENSE)
- [SLF4J license](https://github.com/qos-ch/slf4j/blob/v_2.0.18/LICENSE.txt)
- [Android document access](https://developer.android.com/training/data-storage/shared/documents-files)
- [Android foreground service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types)
