#!/bin/sh
# Gradle wrapper script — generated for kumea-android
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
CLASSPATH="gradle/wrapper/gradle-wrapper.jar"
exec java -cp "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
