# Builds the ORBIT terminal helper for the user's computer. Only Docker is needed - no Go on the computer:
#   docker build --target export --build-arg AGENT_OS=windows --build-arg AGENT_ARCH=amd64 \
#                --output type=local,dest=.orbit-bin terminal-agent
# writes .orbit-bin/orbit-terminal(.exe). scripts/orbit-terminal.cmd / .sh do this for you (start.cmd / start.sh call them).
FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY *.go ./
# unit tests run on Linux for every build (ticket checks, shell detection rules, origin check)
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build go test ./...
ARG AGENT_OS=linux
ARG AGENT_ARCH=amd64
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    EXT=""; if [ "$AGENT_OS" = "windows" ]; then EXT=".exe"; fi; \
    CGO_ENABLED=0 GOOS="$AGENT_OS" GOARCH="$AGENT_ARCH" go build -trimpath -ldflags "-s -w" -o "/out/orbit-terminal$EXT" .

FROM scratch AS export
COPY --from=build /out/ /
