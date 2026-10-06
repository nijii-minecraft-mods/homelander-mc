FROM itzg/minecraft-server:java21

LABEL org.opencontainers.image.title="Homelander Powers Test Server"
LABEL org.opencontainers.image.description="Fabric 1.21.1 smoke-test server for Homelander Powers"

ENV TYPE=FABRIC \
    VERSION=1.21.1 \
    FABRIC_LOADER_VERSION=0.16.5 \
    MEMORY=2G \
    EULA=TRUE \
    ONLINE_MODE=FALSE \
    LEVEL=homelander-test \
    SPAWN_PROTECTION=0 \
    MAX_PLAYERS=8 \
    VIEW_DISTANCE=6 \
    SIMULATION_DISTANCE=4 \
    MOTD="Homelander Powers — test server"

# The workflow stages the exact JAR that passed the build into this context.
COPY --chown=1000:1000 server/mods/*.jar /mods/

EXPOSE 25565/tcp
