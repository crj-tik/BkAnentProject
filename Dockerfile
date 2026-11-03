ARG MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-17@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:17-jre-jammy@sha256:ec72ba5962b45ae4e7f96bfb5ebf6eeb34a488b967f937c8e14f0aaec688954f
FROM ${MAVEN_IMAGE} AS build

WORKDIR /workspace
COPY . .

ARG MAVEN_MODULES=""
RUN if [ -n "$MAVEN_MODULES" ]; then \
      mvn -B -s .mvn-settings.xml -DskipTests -pl "$MAVEN_MODULES" -am package; \
    else \
      mvn -B -s .mvn-settings.xml -DskipTests package; \
    fi

FROM ${JAVA_RUNTIME_IMAGE}

ARG APP_VERSION
LABEL org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${APP_VERSION}"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 10001 app

WORKDIR /app
COPY --from=build /workspace/*/target/*.jar /app/

RUN mkdir -p /run/secrets /app/runtime && chown -R app:app /app/runtime

USER app
ENV JAVA_OPTS=""
ENV SERVICE_ARTIFACT=""

ENTRYPOINT ["sh", "-c", "test -n \"$SERVICE_ARTIFACT\" || { echo 'SERVICE_ARTIFACT is required' >&2; exit 64; }; exec java $JAVA_OPTS -jar /app/$SERVICE_ARTIFACT"]
