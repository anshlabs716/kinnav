FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src

# The relay is built from the Gradle project rather than a single file, so it shares the
# authorisation engine with the Android client instead of keeping a second copy that drifts.
#
# Only :shared and :relay are copied. relayOnly keeps Gradle from configuring :app, which would
# need the Android SDK and pull the entire Android toolchain into a server image.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY shared ./shared
COPY relay ./relay

RUN ./gradlew :relay:installDist --no-daemon -PrelayOnly=true

FROM eclipse-temurin:21-jre-alpine

# The JRE base image already includes the modules HttpServer needs, so no extra packages.
COPY --from=build /src/relay/build/install/relay /opt/relay

EXPOSE 8080

# Bearer tokens travel on every request, so TLS is expected to terminate in front of this
# process, at a reverse proxy or a tunnel. Running it on plain HTTP on a public network would
# expose every token.
ENTRYPOINT ["/opt/relay/bin/relay"]
CMD ["-addr", ":8080", "-max-log", "500"]
