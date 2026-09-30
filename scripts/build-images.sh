#!/bin/sh
# Build the three ORBIT images with a chosen layer-compression algorithm (BuildKit --output).
#
#   scripts/build-images.sh [zstd|gzip|estargz|uncompressed] [--push <registry/prefix>]
#
#   no --push : writes OCI image archives to ./dist/<name>.tar (compressed layers) and prints their sizes
#   --push    : pushes <registry/prefix>/orbit-<name>:latest with the chosen compression
#
# Compression only changes the size of the image when it is STORED / TRANSFERRED (registry, tar, pull). The size that
# `docker images` shows is the uncompressed size and does not change with zstd/gzip/estargz - the structural changes in
# the Dockerfiles (multi-stage, alpine, jlink, slim postgres) are what reduce that number.
set -eu
cd "$(dirname "$0")/.."

ALGO="zstd"; PUSH=""
while [ $# -gt 0 ]; do
  case "$1" in
    zstd|gzip|estargz|uncompressed) ALGO="$1" ;;
    --push) shift; PUSH="${1:?--push needs <registry/prefix>}" ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

# The default "docker" builder cannot choose a compression; a docker-container builder can.
if ! docker buildx inspect orbit-builder >/dev/null 2>&1; then
  docker buildx create --name orbit-builder --driver docker-container >/dev/null
fi

API_URL="${NEXT_PUBLIC_API_URL:-http://localhost:8080}"
mkdir -p dist

build() {  # build <name> <context> [extra build args...]
  name="$1"; ctx="$2"; shift 2
  opts="compression=$ALGO,force-compression=true,oci-mediatypes=true"
  if [ "$ALGO" = "uncompressed" ]; then opts="compression=uncompressed,oci-mediatypes=true"; fi
  if [ -n "$PUSH" ]; then
    out="type=image,name=$PUSH/orbit-$name:latest,push=true,$opts"
  else
    out="type=oci,dest=dist/orbit-$name.tar,$opts"
  fi
  echo "==> orbit-$name  ($ALGO)"
  docker buildx build --builder orbit-builder --output "$out" "$@" "$ctx"
}

build backend  ./backend
build frontend ./frontend --build-arg "NEXT_PUBLIC_API_URL=$API_URL"
build postgres ./infrastructure/postgresql

if [ -z "$PUSH" ]; then
  echo; echo "Compressed archive sizes ($ALGO):"; ls -l dist/orbit-*.tar | awk '{printf "  %-28s %8.1f MB\n", $9, $5/1048576}'
fi
