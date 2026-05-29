# Scaffold Notes — Ticket 2.1

Decisions made during the initial scaffold, with rationale. Read this if you're
reviewing the PR or extending the scaffold in 2.2 / 2.3 and wondering "why was
this done this way."

## Version catalog deviations from ticket spec

Two intentional changes, approved during scaffolding:

| Item | Ticket version | Actual version | Reason |
|---|---|---|---|
| `ksp` | `2.0.0-1.0.21` | `2.0.0-1.0.22` | `1.0.21` had a confirmed `NoSuchMethodError` with Dagger that breaks Hilt builds. Fixed in `1.0.22`. |
| `retrofit-kotlinx-serialization-converter` | `com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0` | `com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0` | Square absorbed Jake Wharton's converter into the official Retrofit project at 2.10+. Same code (unchanged from JW's 1.0.0), now versioned in lockstep with Retrofit itself. Import path is unchanged (`com.jakewharton.retrofit2.converter.kotlinx.serialization`) because Square didn't repackage the classes. |

A new artifact was added that wasn't in the ticket catalog:

| Artifact | Why |
|---|---|
| `androidx.lifecycle:lifecycle-runtime-compose` | `collectAsStateWithLifecycle()` lives here, not in `lifecycle-runtime-ktx`. Required by `PingScreen`. |

## Gradle wrapper

`gradle-wrapper.jar` is not checked in — see `gradle/wrapper/README.md` for the
one-time bootstrap. Wrapper version pinned to **Gradle 8.9** (minimum supported
for AGP 8.5.0 is 8.7; 8.9 is the conservative stable choice from the time AGP
8.5.0 shipped).

## Configuration cache

Disabled by default in `gradle.properties` (`org.gradle.configuration-cache=false`).
Hilt + AGP 8.5 has edge cases that produce confusing first-build failures.
Re-enable in Sprint 1 once stability is confirmed.

## OkHttp logging interceptor

Spec said "active in debug, absent in release." Implementation: the artifact is
on `implementation` (not `debugImplementation`) so the import resolves in
release, but `NetworkModule` only instantiates the interceptor when
`BuildConfig.DEBUG` is true. Net result: zero log lines in release builds, the
class is dead code that R8 strips. The acceptance criterion is met at the
behavior level (which is what matters).

## Adaptive icon

Marcus's foreground XML had the green background baked into the foreground
path. For adaptive icons (API 26+), background must be a separate layer so the
launcher's mask (circle / squircle / rounded square — varies by OEM) clips
correctly. Split into:

- `drawable/ic_launcher_foreground.xml` — K monogram on transparent
- `values/colors.xml` `ic_launcher_background` — `#2E7D32`
- `mipmap-anydpi-v26/ic_launcher{,_round}.xml` — adaptive-icon descriptors

For pre-API-26 devices (24, 25 — the bottom of our minSdk range), the original
"background baked in" vector is kept as `drawable/ic_launcher_legacy.xml` and
aliased into `mipmap-anydpi/ic_launcher{,_round}.xml`. These versions don't
have adaptive icon support, so the flat square is the correct rendering.

The `<monochrome>` layer for Android 13+ themed icons points at the same
foreground drawable — the K is already white on transparent, which is what the
themed-icon system wants.

## Room database with no entities

Per ticket spec, `KumeaDatabase` uses `@Database(entities = [])`. If Room
rejects this at KSP time, the fix is to add a placeholder entity. Will know
on first build.

## AuthInterceptor wired live (not stubbed)

Confirmed during scaffolding: the interceptor is on the OkHttpClient builder
from day one, reading `TokenStore.tokenFlow` via `runBlocking`. When no token
is saved (i.e. always, in Sprint 0), the interceptor is a no-op pass-through.
This proves the full DI chain (TokenStore → AuthInterceptor → OkHttpClient
→ Retrofit → KumeaApi) compiles and resolves.

## DatabaseModule provides the empty database

Confirmed during scaffolding: `DatabaseModule` provides `KumeaDatabase` even
though it's empty. This proves Room + KSP + Hilt all wire up. 2.2 adds the
first entities (`Farm`, `Field`).
