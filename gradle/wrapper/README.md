# Gradle wrapper bootstrap

This scaffold was generated in an offline environment, so
`gradle-wrapper.jar` is **not checked in yet**. You need to generate it once,
on first checkout, then commit it so CI and other contributors can build.

## One-time setup (any one option works)

**Option A — you have a system Gradle installed:**

```bash
gradle wrapper --gradle-version 8.9 --distribution-type bin
```

This reads `gradle/wrapper/gradle-wrapper.properties` and regenerates
`gradle-wrapper.jar` + the wrapper scripts. Then:

```bash
git add gradle/wrapper/gradle-wrapper.jar
git commit -m "Add gradle-wrapper.jar"
```

**Option B — you don't have Gradle but you have Android Studio:**

1. Open the project in Android Studio.
2. When prompted to install a Gradle distribution, accept.
3. After the first sync completes, Android Studio writes the jar to
   `gradle/wrapper/gradle-wrapper.jar` automatically.
4. Commit it.

**Option C — fetch the jar directly from the Gradle 8.9 distribution:**

```bash
curl -fL https://services.gradle.org/distributions/gradle-8.9-bin.zip -o /tmp/gradle.zip
unzip -p /tmp/gradle.zip 'gradle-8.9/lib/plugins/gradle-wrapper-*.jar' \
  > gradle/wrapper/gradle-wrapper.jar
```

After committing the jar, this file can be deleted.

## Why isn't it committed already?

The scaffold was produced without internet access, so binary artifacts couldn't
be downloaded into the repo. Everything else (scripts, properties, build files)
is final and correct — the jar is the one binary blob that has to come from a
trusted source.

## Verify the jar after generating

The Gradle 8.9 wrapper jar SHA-256 should match the one published at
<https://services.gradle.org/distributions>. The wrapper itself checks the
distribution download against the SHA in `gradle-wrapper.properties` once you
run `./gradlew` for the first time.
