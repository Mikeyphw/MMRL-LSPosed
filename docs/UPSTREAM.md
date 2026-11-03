# Source provenance and attribution

The standalone project retains GPL-3.0 licensing and documents a direct relationship to the separate `Mikeyphw/MMRL` Android project (original upstream: `MMRLApp/MMRL`).

- Original project: https://github.com/MMRLApp/MMRL
- User's maintained MMRL fork: https://github.com/Mikeyphw/MMRL
- Vector Phase06 base: `b8410ce024b921d96c384e7b45d26929625418ab`
- Phase07 reviewed read-only candidate: `7312cc03587cf60ef0c7010810b012d677ee5f1f`
- Standalone initial parent commit: `5e0efe8802729db1201e539097e1dc4d6b63cb24`
- Gradle wrapper `gradlew`, `gradle-wrapper.jar` and `gradle-wrapper.properties` sourced from the Phase07 commit above and checked against exact Git blob SHA1 identities plus wrapper JAR SHA256 `d3b261c2820e9e3d8d639ed084900f11f4a86050a8f83342ade7b6bc9b0d2bdd`.
- Official Gradle distribution version `9.1.0` validated by Gradle's wrapper distribution checksum in the properties file.

S01's Kotlin Compose app and unprivileged module APK detector are new code. There is no copied MMRL Android root manager implementation, hidden API, framework transport, root Binder service, device hooks or root provider in S01. Future migration of verified Vector backend must credit imported files and maintain license compliance.

Future shared Kotlin components are explicitly versioned projects, not Devtool federation `sync` operations.
