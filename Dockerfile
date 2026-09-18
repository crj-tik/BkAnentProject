FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY . .

ARG MAVEN_MODULES=""
RUN if [ -n "$MAVEN_MODULES" ]; then \
      mvn -B -s .mvn-settings.xml -DskipTests -pl "$MAVEN_MODULES" -am package; \
    else \
      mvn -B -s .mvn-settings.xml -DskipTests package; \
    fi

FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 10001 app

WORKDIR /app
COPY --from=build /workspace/*/target/*.jar /app/

RUN mkdir -p /app/runtime && chown -R app:app /app/runtime

USER app
ENV JAVA_OPTS=""
ENV SERVICE_ARTIFACT=""

ENTRYPOINT ["sh", "-c", "test -n \"$SERVICE_ARTIFACT\" || { echo 'SERVICE_ARTIFACT is required' >&2; exit 64; }; exec java $JAVA_OPTS -jar /app/$SERVICE_ARTIFACT"]
