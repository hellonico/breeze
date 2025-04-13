# ---- Build stage ----
FROM eclipse-temurin:21-jdk AS builder

# Install Node.js (includes npx)
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y nodejs

# Install Clojure CLI
RUN curl -O https://download.clojure.org/install/linux-install-1.11.1.1273.sh && \
    chmod +x linux-install-1.11.1.1273.sh && \
    ./linux-install-1.11.1.1273.sh && \
    rm linux-install-1.11.1.1273.sh

WORKDIR /app

# Copy configs and deps
COPY deps.edn package.json shadow-cljs.edn ./
COPY src ./src
COPY public ./public

# Install JS deps
RUN npm install

#
RUN sed -i 's|http://localhost:11434|http://host.docker.internal:11434|g' ./src/myapp/core.cljs

# Compile ClojureScript
RUN npx shadow-cljs release app

# Build backend uberjar
# TODO: move this in the COPY above
COPY build.clj ./
RUN apt-get update && apt-get install -y git bash curl coreutils
RUN clojure -T:build uber

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar
COPY --from=builder /app/public ./public

EXPOSE 3000

ENTRYPOINT ["java", "-jar", "app.jar"]
