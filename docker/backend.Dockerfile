# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode --quiet dependency:go-offline
COPY src/main src/main
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode --quiet -Dmaven.test.skip=true compile \
    && cp -a /root/.m2 /opt/maven-cache
ENV MAVEN_OPTS="-Dmaven.repo.local=/opt/maven-cache"
COPY docker/backend-entrypoint.sh /opt/backend-entrypoint.sh

EXPOSE 8080
ENTRYPOINT ["sh", "/opt/backend-entrypoint.sh"]
