#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
mvn -q -pl poker-server -am install -DskipTests
mvn -q -pl poker-server dependency:build-classpath -Dmdep.outputFile=target/runtime-classpath.txt
CP="poker-server/target/classes:$(cat poker-server/target/runtime-classpath.txt)"
java -cp "$CP" cn.friendpoker.server.user.PasswordHashTool
