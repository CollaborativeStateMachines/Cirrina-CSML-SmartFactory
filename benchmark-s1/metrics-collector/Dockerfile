FROM collaborativestatemachines/cirrina:unstable AS cirrina

FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY --from=cirrina /opt/cirrina/lib /opt/cirrina/lib
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle.properties ./
COPY src ./src
RUN ./gradlew shadowJar --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /collector
COPY --from=cirrina /opt/cirrina/lib /opt/cirrina/lib
COPY --from=build /app/build/libs/CSMMetricsCollector.jar CSMMetricsCollector.jar
RUN mkdir -p metrics

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "-cp", "/collector/CSMMetricsCollector.jar:/opt/cirrina/lib/*", "at.ac.uibk.dps.cirrina.execution.object.MetricsCollectorKt"]