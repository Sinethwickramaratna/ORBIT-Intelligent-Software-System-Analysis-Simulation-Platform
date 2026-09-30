# Slimmer, single-layer PostgreSQL 16 image. Behaviour is identical to postgres:16-alpine; two things change:
#   1. the JIT compiler (LLVM, by far the biggest part of the image) is removed - ORBIT's queries are tiny, JIT never helps
#   2. the filesystem is flattened into ONE layer (the same effect as `docker-squash`), so nothing deleted in an
#      earlier layer keeps taking up space
# NOTE: flattening drops the parent's image metadata, so ENV / ENTRYPOINT / CMD / VOLUME etc. are restated below.
FROM postgres:16-alpine AS slim
RUN set -eux; \
    # JIT provider + its bitcode, then the LLVM runtime libraries that only the JIT provider links against
    rm -rf /usr/local/lib/postgresql/llvmjit.so /usr/local/lib/postgresql/bitcode; \
    rm -f /usr/lib/libLLVM*.so* /usr/lib/libLLVM* ; \
    # docs, man pages, caches, sample configs
    rm -rf /usr/local/share/doc /usr/local/share/man /usr/share/man /usr/share/doc /var/cache/apk/* /tmp/*; \
    # build-time smoke test: the binaries still start after the clean-up (the build fails here if they do not)
    su-exec postgres postgres --version; \
    su-exec postgres initdb --version; \
    su-exec postgres pg_dump --version

FROM scratch
COPY --from=slim / /
ENV LANG=en_US.utf8 \
    PG_MAJOR=16 \
    PGDATA=/var/lib/postgresql/data \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
VOLUME /var/lib/postgresql/data
STOPSIGNAL SIGINT
EXPOSE 5432
ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["postgres"]
