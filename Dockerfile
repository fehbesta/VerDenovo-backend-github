# ---- Etapa 1: build ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
# Baixa as dependências antes de copiar o código-fonte, para aproveitar cache
# de camadas do Docker em rebuilds (acelera deploys seguintes).
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q clean package -DskipTests

# ---- Etapa 2: execução ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Render injeta a porta real na variável de ambiente PORT.
# server.port no application.properties (8080) é usado só localmente;
# em produção, sobrescrevemos via -Dserver.port.
ENV PORT=8080

# Limita o heap da JVM para caber em hosts com pouca RAM (ex: 512MB no Render free).
# Ajuste -Xmx se o host tiver mais memória disponível.
ENTRYPOINT ["sh", "-c", "java -Xmx256m -Dserver.port=$PORT -jar app.jar"]
