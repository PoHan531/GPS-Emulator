#!/bin/sh
#
# Gradle startup script for UN*X
#

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`

DIRNAME=`dirname "$0"`
cd "$DIRNAME" >/dev/null
APP_HOME=`pwd -P`
cd - >/dev/null

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

if [ "$1" = "" ]; then
  set -- "$@"
fi

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

JAVA_EXE=java

exec "$JAVA_EXE" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
  "-Dorg.gradle.appname=$APP_BASE_NAME" \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
