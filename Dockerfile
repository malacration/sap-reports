# ============================================================
# Build em duas etapas, com layers do Spring Boot.
#
# Diferenca em relacao ao sap-rovema: este projeto usa Spring Boot 4,
# onde `-Djarmode=layertools` foi REMOVIDO ("Unsupported jarmode").
# O equivalente agora e `-Djarmode=tools extract --layers`.
# ============================================================
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace/app

# O wrapper e os metadados primeiro: enquanto as dependencias nao mudam,
# esta camada fica em cache e o build nao rebaixa tudo a cada alteracao de codigo.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x ./gradlew && ./gradlew dependencies --no-daemon > /dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

RUN rm -f build/libs/*-plain.jar && \
    java -Djarmode=tools -jar build/libs/*.jar extract --layers --launcher --destination /extracted

# ============================================================
FROM eclipse-temurin:25-jre
WORKDIR /app

# Usuario sem privilegio: a aplicacao nao precisa de root.
RUN groupadd -r spring && useradd -r -g spring spring

ARG EXTRACTED=/extracted
COPY --from=build --chown=spring:spring ${EXTRACTED}/dependencies/ ./
COPY --from=build --chown=spring:spring ${EXTRACTED}/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring ${EXTRACTED}/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring ${EXTRACTED}/application/ ./

USER spring
EXPOSE 8080

# Credenciais entram por variavel de ambiente, nunca na imagem:
#   docker run -e SAP_JDBC_URL=... -e SAP_DB_USER=... -e SAP_DB_PASSWORD=... -e API_KEY=...
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
