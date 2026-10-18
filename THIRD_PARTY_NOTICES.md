# Third-party components in DiLink Inspector 2.0.0

The application bundles these fixed dependency versions. Download URLs and SHA-256 checksums are in `vendor/dependencies.lock.json`. Runtime use is offline.

- LibADB Android 3.1.1 — https://github.com/MuntashirAkon/libadb-android/tree/3.1.1 — Copyright its contributors, including Cameron Gutman. Used under the offered Apache-2.0 option and applicable BSD-3-Clause notices. Source and licenses are included. Local changes in `tools/prepare_vendor.py`: bounded TCP connect/thread join/stream opening, lost-notification and pending-EOF fixes, externally owned identity lifetime, UTF-8 destination byte lengths, negotiated buffer cap, pairing timeouts and cancellation/null-safe cleanup. Android ADB RSA authentication and TLS pairing still require the target's authorization.
- SPAKE2 Android 2.2.1 — https://github.com/MuntashirAkon/spake2-java/tree/2.2.1 — Apache-2.0. Includes native libraries for arm64-v8a, armeabi-v7a, x86, x86_64.
- Conscrypt Android 2.5.3 — https://github.com/google/conscrypt — Apache-2.0 and bundled third-party notices. Native TLS provider, same four ABIs.
- Bouncy Castle bcprov/bcpkix/bcutil 1.81 — https://www.bouncycastle.org/licence.html — Bouncy Castle license. Used for certificate creation and library cryptography.
- AndroidX annotation 1.3.0 — https://developer.android.com/jetpack/androidx/releases/annotation — Apache-2.0, compile-time annotations.

ADB identities are generated on the Android device and kept in the app's private no-backup directory. They are independent of the APK signing certificate and are excluded from diagnostic exports.
