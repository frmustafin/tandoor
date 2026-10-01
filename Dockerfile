# Stage 1: build the executable jar with the project's own Gradle wrapper (9.7.1).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Build scripts first, so dependency download is cached while only sources change.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet > /dev/null 2>&1 || true

COPY src ./src
# Tests run locally before a commit; the image build only packages.
RUN ./gradlew --no-daemon bootJar -x test --quiet

# Stage 2: OpenJ9 runtime, chosen for its small footprint on the 0.5 GB tier.
FROM ibm-semeru-runtimes:open-21-jre
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar

# H2 database file lives on a persistent volume mounted at /data.
VOLUME /data
ENV DB_URL="jdbc:h2:file:/data/tandoor;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"

# Heap is half of the container limit; the rest is for metaspace, threads and H2.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -Xss512k -Xtune:virtualized -Xshareclasses:cacheDir=/tmp/jsc"

ENTRYPOINT ["java", "-jar", "app.jar"]
