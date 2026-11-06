#!/usr/bin/env bash
export JAVA_HOME="$(find "$PWD/.tools" -maxdepth 1 -type d -name 'jdk-*' | head -1)"
export ANDROID_HOME="$PWD/.tools/android"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PWD/.tools/gradle-8.10.2/bin:$PATH"
