# One image recipe for every Spring Boot module. Build context is the repo root:
#   docker build -f docker/java-service.Dockerfile \
#     --build-arg SERVICE=auth-service \
#     --build-arg MAIN_CLASS=com.callback.auth.AuthServiceApplication .
#
# The module jars have no Main-Class (the parent POM imports the Boot BOM rather than inheriting
# spring-boot-starter-parent, so repackage never runs), so the image runs the module from a
# classpath: its compiled classes plus every runtime dependency copied into lib/.

FROM eclipse-temurin:21-jdk AS build
ARG SERVICE
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl "${SERVICE}" -am package dependency:copy-dependencies \
        -DskipTests -DincludeScope=runtime -DoutputDirectory=/out/lib \
    && cp -r "${SERVICE}/target/classes" /out/classes

FROM eclipse-temurin:21-jre
ARG MAIN_CLASS
ENV MAIN_CLASS=${MAIN_CLASS} JAVA_OPTS=""
RUN useradd --system --home /app app && mkdir -p /app/data/resumes && chown -R app /app
WORKDIR /app
COPY --from=build /out/lib ./lib
COPY --from=build /out/classes ./classes
USER app
# exec so the JVM is PID 1 and receives SIGTERM from `docker stop`.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp 'classes:lib/*' $MAIN_CLASS"]
