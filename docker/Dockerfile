# Cabal SMP — Fabric server runtime (Java 25). Game files live on the mounted ./server volume.
FROM eclipse-temurin:25-jre-noble

ENV LANG=C.UTF-8 \
    MC_SERVER_DIR=/data \
    MC_SERVER_PORT=25565 \
    MC_RCON_PORT=25575

WORKDIR /data

COPY scripts/start.sh /opt/cabal/scripts/start.sh
RUN chmod +x /opt/cabal/scripts/start.sh

ENTRYPOINT ["/opt/cabal/scripts/start.sh"]
