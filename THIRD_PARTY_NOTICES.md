# Third-party components

Retroid Settings is built with the libraries below. They are fetched by Gradle and are not part of this
source tree; the release APK contains compiled copies of the runtime ones.

## Runtime libraries (inside the APK)

| Library | Version | License | Source |
|---|---|---|---|
| LibADB Android | 3.1.1 | Apache-2.0 **or** GPL-3.0-or-later (dual; used here under Apache-2.0) | https://github.com/MuntashirAkon/libadb-android |
| spake2-android (spake2-java), pulled in by LibADB | 2.2.1 | LGPL-3.0 | https://github.com/MuntashirAkon/spake2-java |
| sun-security-android | 1.1 | GPL-2.0 with the Classpath Exception (derived from OpenJDK) | https://github.com/MuntashirAkon/sun-security-android |
| Bouncy Castle (bcprov-jdk15to18), pulled in by LibADB | 1.81 | Bouncy Castle License (MIT-style) | https://www.bouncycastle.org/licence.html |
| AndroidHiddenApiBypass | 6.1 | Apache-2.0 | https://github.com/LSPosed/AndroidHiddenApiBypass |
| AndroidX, Jetpack Compose, Material 3, Material Icons Extended | see `app/build.gradle.kts` | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| Kotlin standard library and coroutines | 2.1.21 | Apache-2.0 | https://kotlinlang.org |
| Guava ListenableFuture, JSpecify (transitive) | 1.0 / 1.0.0 | Apache-2.0 | https://github.com/google/guava · https://jspecify.dev |

## Build tools (not shipped)

| Tool | License |
|---|---|
| Gradle and the Gradle Wrapper (`gradle/wrapper/gradle-wrapper.jar`) | Apache-2.0 |
| Android Gradle Plugin, R8 | Apache-2.0 |

## Notes on the copyleft parts

- **sun-security-android** is GPL-2.0 with the Classpath Exception: linking it into an app does not put the
  app under the GPL. Its source is available at the link above.
- **spake2-java** is LGPL-3.0: users must be able to get its source (link above) and to replace the library.
  The release build shrinks and obfuscates code with R8; the R8 rules keep LibADB's classes, and the full
  source of this app is published here, so the APK can be rebuilt with a modified library.

## This project

All other code, the control daemon and helper scripts (`app/src/main/assets`), and the artwork (app icon,
tile icons, logo) were written for this project. No code from other apps is included.

Retroid and Retroid Pocket are trademarks of their respective owners. LineageOS is a trademark of the
LineageOS project. This project is not affiliated with or endorsed by either.
