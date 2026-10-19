# One image recipe for every Spring Boot module. Build context is the repo root:
#   docker build -f docker/java-service.Dockerfile --build-arg SERVICE=auth-service .
#
# Each runnable module declares spring-boot-maven-plugin (repackage is bound in the root pom's
# pluginManagement), so target/<SERVICE>.jar is an executable Boot jar. common-security is a plain
# library jar and is bundled into the services that depend on it.

FROM eclipse-temurin:21-jdk AS build
ARG SERVICE
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl "${SERVICE}" -am -DskipTests package

FROM eclipse-temurin:21-jre
ARG SERVICE
ENV JAVA_OPTS=""
RUN useradd --system --home /app app && mkdir -p /app/data/resumes && chown -R app /app
WORKDIR /app
COPY --from=build /src/${SERVICE}/target/${SERVICE}.jar app.jar
USER app
# exec so the JVM is PID 1 and receives SIGTERM from `docker stop`.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
