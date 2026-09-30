# ---- build: Node is only needed to produce the static site ---------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /app
# NEXT_PUBLIC_* values are baked into the browser bundle at build time.
# This is the address the *browser* uses to reach the backend.
ARG NEXT_PUBLIC_API_URL=http://localhost:8080
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL \
    NEXT_TELEMETRY_DISABLED=1
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
# `output: "export"` writes the finished site to /app/out
RUN npm run build

# ---- runtime: nginx only, no Node.js, no node_modules ---------------------------------------------------------------
FROM nginxinc/nginx-unprivileged:alpine-slim
# nginx-unprivileged runs as a non-root user; it listens on 3000 via our config
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/out /usr/share/nginx/html
EXPOSE 3000
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=5 \
  CMD wget -qO- http://127.0.0.1:3000/ >/dev/null || exit 1
