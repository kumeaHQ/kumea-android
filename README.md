# kumea-android

Android client for Kumea. Sprint 0 scaffold — single screen, single button, single HTTPS call.

## Status

This is the Ticket 2.1 scaffold. There's one screen (`PingScreen`) that hits
`GET /health` against the production API. Real features land in tickets 2.2
(offline-first sync) and 2.3 (login + farm list).

## Prerequisites

- **JDK 17** — required by AGP 8.5. Higher JDKs may work but 17 is what CI uses.
- **Android Studio** Koala (2024.1.1) or newer for IDE work. Command-line builds need only the Android SDK + JDK 17.
- **Android SDK** with platform 35 installed.

Check your JDK with:

```bash
java -version
# openjdk version "17.x.x" — anything else, point JAVA_HOME at a JDK 17 install
```

## Clone and open

```bash
git clone <repo-url> kumea-android
cd kumea-android
```

Open the `kumea-android` folder in Android Studio. First sync downloads the
Gradle distribution and dependencies — give it a few minutes on first run.

## Run on device

1. Enable Developer Options + USB debugging on your phone.
2. Plug it in. It should appear in the device dropdown in Android Studio.
3. Hit Run (▶) — the app installs and launches.
4. Tap **Ping API**. Within a few seconds you should see `status: ok, database: ok` plus uptime and timestamp.
5. Toggle airplane mode and tap again — you should see an error message, not a crash.

## Run on emulator

Any Pixel image at **API 24+** works. Cold boot, then Run. Same UX as on-device,
though emulators sometimes mask DNS quirks that real devices expose, so always
sanity-check on hardware before merging changes that touch networking.

## API base URL

Configured per-build-type in `app/build.gradle.kts`:

```kotlin
debug {
    buildConfigField("String", "API_BASE_URL", "\"https://kumea-api.up.railway.app/\"")
}
release {
    buildConfigField("String", "API_BASE_URL", "\"https://kumea-api.up.railway.app/\"")
}
```

To point at a different backend (local server via [adb reverse], staging, etc.):

1. Edit the relevant `buildConfigField` line.
2. Trigger a Gradle sync.
3. Rebuild. `BuildConfig.API_BASE_URL` is generated at compile time.

Note: the `network_security_config.xml` rejects cleartext by default. If you
need to hit `http://10.0.2.2:8000/` from an emulator for local dev, add a
`<domain-config>` entry permitting cleartext for that host only — don't flip
the global `cleartextTrafficPermitted`.

## CI artifacts

GitHub Actions runs lint + unit tests + `assembleDebug` on every push to `main`
and every PR. The debug APK is uploaded as an artifact named `kumea-debug-apk`.

To grab it without Android Studio:

1. Go to the **Actions** tab on GitHub.
2. Click into the relevant workflow run.
3. Scroll to **Artifacts** at the bottom — download `kumea-debug-apk.zip`.
4. Unzip, then `adb install <file>.apk`.

This is the easiest way to get the app onto Marcus's phone without a dev setup.

## Project layout

```
app/src/main/java/co/ke/kumea/
├── KumeaApplication.kt         # @HiltAndroidApp entry point
├── MainActivity.kt             # Single Activity, hosts Compose
├── data/
│   ├── local/                  # Room database (empty for now)
│   ├── remote/                 # Retrofit interfaces + DTOs + interceptors
│   ├── repository/             # Repository pattern wrappers
│   └── auth/                   # TokenStore (DataStore Preferences)
├── domain/model/               # Placeholder — fills out in 2.2/2.3
├── ui/
│   ├── theme/                  # Material 3 theme + brand colors
│   ├── navigation/             # NavHost
│   └── screen/ping/            # The verification screen
├── di/                         # Hilt modules
└── util/                       # Tiny helpers only
```

## Stack

| Layer            | Choice                              |
|------------------|-------------------------------------|
| Language         | Kotlin 2.0.0                        |
| UI               | Jetpack Compose (Material 3)        |
| DI               | Hilt                                |
| Local DB         | Room (empty in this ticket)         |
| Network          | Retrofit + OkHttp                   |
| JSON             | kotlinx-serialization (Square's first-party Retrofit converter) |
| Timestamps       | kotlinx-datetime                    |
| Async            | Coroutines + Flow                   |
| Token storage    | Preferences DataStore               |
| Navigation       | Navigation Compose                  |
| Build            | Gradle 8.9 + Kotlin DSL + Version Catalog |
| minSdk / target  | 24 / 35                             |

All versions are pinned in `gradle/libs.versions.toml`. Don't put hardcoded
versions in `build.gradle.kts` files — go through the catalog.

## The Ping screen is temporary

`PingScreen` exists solely to verify the API pipe end-to-end on real hardware.
Once 2.3 lands the login flow, this screen disappears (or moves behind a debug
flag). Don't build on top of it.

## Troubleshooting

**Gradle sync fails on first open** — most often a JDK version mismatch. File →
Project Structure → SDK Location → Gradle JDK. Pick a JDK 17.

**`./gradlew assembleDebug` fails with "SDK location not found"** — create a
`local.properties` with `sdk.dir=/path/to/Android/sdk`. This file is gitignored.

**Ping button shows "Unable to resolve host"** — the device has no network.
Real-device failure mode for Kenyan 2G; the 30-second OkHttp timeout will fire.

**Ping button shows TLS / certificate error** — check the device clock. Stale
clocks cause cert validation to fail.
