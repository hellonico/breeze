# ---- Build stage ----
FROM eclipse-temurin:21-jdk AS builder

# Install core tools, especially git
RUN apt-get update && apt-get install -y git bash curl coreutils

# Install Node.js (includes npx)
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y nodejs

# Install Clojure CLI
RUN curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh && \
    chmod +x linux-install.sh && \
    ./linux-install.sh && \
    rm linux-install.sh

WORKDIR /app

# Copy configs and deps
COPY deps.edn package.json shadow-cljs.edn build.clj ./
COPY src ./src
COPY public ./public

# Install JS deps
RUN npm install

# customized ollama url to refer to the host ip
RUN sed -i 's|http://localhost:11434|http://host.docker.internal:11434|g' ./src/myapp/core.cljs

# Compile ClojureScript
RUN npx shadow-cljs release app

# Build backend uberjar
RUN clojure -T:build uber

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre

WORKDIR /app

# the jar contains the compiled clojure code
COPY --from=builder /app/target/*.jar app.jar
# the public folder, contains the static html+css, and the compiled clojurescript -> javascript code
COPY --from=builder /app/public ./public

EXPOSE 3000

ENTRYPOINT ["java", "-jar", "app.jar"]
