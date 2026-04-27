#!/bin/sh
# Gradle start up script for UN*X

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")

# Resolve links: $0 may be a link
PRG="$0"
while [ -h "$PRG" ] ; do
    ls=$(ls -ld "$PRG")
    link=$(expr "$ls" : '.*-> \(.*\)$')
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=$(dirname "$PRG")/"$link"
    fi
done
SAVED=$(pwd)
cd $(dirname "$PRG") > /dev/null
APP_HOME=$(pwd -P)
cd "$SAVED" > /dev/null

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

JAVA_EXE=java
if ! command -v java > /dev/null 2>&1; then
    JAVA_HOME_CANDIDATES="${JAVA_HOME:-}"
    for candidate in $JAVA_HOME_CANDIDATES; do
        if [ -x "$candidate/bin/java" ]; then
            JAVA_EXE="$candidate/bin/java"
            break
        fi
    done
fi

exec "$JAVA_EXE" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
