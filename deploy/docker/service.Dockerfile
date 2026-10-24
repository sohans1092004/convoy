# One image recipe for every Spring Boot service. The jar is built on the host/CI first:
#   ./gradlew bootJar   ->   services/<name>/build/libs/<name>.jar
FROM eclipse-temurin:21.0.12.1_1-jre-alpine
ARG SERVICE
ARG OTEL_AGENT_VERSION=2.33.0
RUN addgroup -S app && adduser -S app -G app && mkdir -p /keys && chown app:app /keys \
 && wget -q -O /opt/otel-agent.jar \
    "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar"
COPY services/${SERVICE}/build/libs/${SERVICE}.jar /opt/app.jar
USER app
# Small heaps: the lite profile must fit many JVMs on one host (P0 risk 5; measured in P1).
ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/otel-agent.jar -XX:+UseSerialGC -Xss512k -XX:MaxRAMPercentage=70 -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/opt/app.jar"]
