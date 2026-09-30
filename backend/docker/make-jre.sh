#!/bin/sh
# Builds a minimal Java runtime (jlink) that contains ONLY the JDK modules the application needs.
# usage: make-jre.sh <fat-jar> <output-dir>
# Used by the Dockerfile; it only needs a JDK (jdeps + jlink) and works on Alpine's ash.
set -eu
JAR="$1"; OUT="$2"
WORK="$(mktemp -d)"

# Modules that Spring Boot / Hibernate / Hikari / the PostgreSQL driver / jjwt reach via reflection or
# optional code paths, which static analysis can miss. Always included (all are small).
SAFE="java.base,java.logging,java.xml,java.naming,java.sql,java.management,java.desktop,java.instrument,java.prefs,java.security.jgss,java.security.sasl,java.net.http,java.transaction.xa,jdk.unsupported,jdk.crypto.ec,jdk.management,jdk.httpserver,jdk.zipfs"

# Spring Boot's own tool unpacks the nested jars so jdeps can look inside them.
DETECTED=""
if java -Djarmode=tools -jar "$JAR" extract --destination "$WORK/x" >/dev/null 2>&1; then
  APP="$(ls "$WORK"/x/*.jar | head -n 1)"
  DETECTED="$(jdeps --multi-release 17 --ignore-missing-deps --print-module-deps \
      --class-path "$WORK/x/lib/*" "$APP" 2>/dev/null || true)"
fi
echo "[make-jre] jdeps detected: ${DETECTED:-<nothing, using the safe list only>}"

# Union of detected + safe list, restricted to modules that really exist in this JDK.
AVAILABLE="$(java --list-modules | sed 's/@.*//')"
MODS=""
for m in $(printf '%s,%s' "$DETECTED" "$SAFE" | tr ',' ' '); do
  if printf '%s\n' "$AVAILABLE" | grep -qx "$m"; then
    case ",$MODS," in *",$m,"*) ;; *) MODS="${MODS:+$MODS,}$m" ;; esac
  else
    echo "[make-jre] skipping unknown module $m"
  fi
done
echo "[make-jre] jlink modules: $MODS"

rm -rf "$OUT"
# JDK 17 only knows numeric levels (--compress=2 = zip); JDK 21+ also accepts zip-6 and deprecates the numbers.
if jlink --help 2>&1 | grep -q 'zip-\[0-9\]'; then COMPRESS="zip-6"; else COMPRESS="2"; fi
echo "[make-jre] jlink --compress=$COMPRESS"
jlink --add-modules "$MODS" --strip-debug --no-man-pages --no-header-files --compress="$COMPRESS" --output "$OUT"
"$OUT/bin/java" -version
rm -rf "$WORK"
