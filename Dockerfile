# Stage 1: build the pinned Forge engine (slow the first time, ~10-20 min)
FROM maven:3.9.11-eclipse-temurin-17 AS build
ARG FORGE_REF=a198319ac46debbc5dc0b3e6fec284360e511930
WORKDIR /upstream
RUN curl -fL "https://codeload.github.com/Card-Forge/forge/tar.gz/${FORGE_REF}" -o /tmp/forge.tar.gz \
    && tar -xzf /tmp/forge.tar.gz --strip-components=1 && rm /tmp/forge.tar.gz
RUN mvn -B -pl forge-gui-desktop -am -DskipTests -Dcheckstyle.skip install
RUN mvn -B org.apache.maven.plugins:maven-install-plugin:3.1.3:install-file \
    -Dfile=forge-gui-desktop/target/forge-gui-desktop-2.0.16-SNAPSHOT-jar-with-dependencies.jar \
    -DgroupId=com.nerdz.upstream -DartifactId=forge-headless -Dversion=${FORGE_REF} -Dpackaging=jar -DgeneratePom=true
# Stage 2: build this server
WORKDIR /server
COPY pom.xml ./
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy
RUN useradd --create-home --uid 10001 forge
WORKDIR /app
COPY --from=build /server/target/companion-server-0.8.0.jar ./server.jar
COPY --from=build /server/target/lib ./lib
COPY --from=build /upstream/forge-gui-desktop/target/forge-gui-desktop-2.0.16-SNAPSHOT-jar-with-dependencies.jar ./forge/forge-headless.jar
COPY --from=build /upstream/forge-gui/res ./res
COPY --from=build /upstream/LICENSE ./FORGE-LICENSE
COPY fixtures ./fixtures
ENV FORGE_ASSETS=/app
USER forge
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx200m", "-cp", "/app/server.jar:/app/lib/*", "com.playtest.App"]
