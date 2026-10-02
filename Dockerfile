# Build multi-etapa: no depende de tener Maven/JDK instalados en el host, solo Docker.
# (Los Dockerfile de judeca/minos/radamanto asumen `mvn clean install` ya corrido en el host antes
# de `docker build` -- aquí se deja todo dentro del build para que `docker build .` sea el único
# paso, más portable para levantar el simulador en una máquina/CI limpia.)
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# eclipse-temurin:17-jre-alpine solo publica linux/amd64 -- sin build para arm64 (falla en Apple
# Silicon con "no match for platform in manifest"). eclipse-temurin:17-jre (Ubuntu, sin Alpine) sí
# publica linux/arm64 -- más pesada, pero portable entre arquitecturas, que es lo que importa aquí.
FROM eclipse-temurin:17-jre
WORKDIR /app
# useradd (no adduser -D, que es sintaxis de BusyBox/Alpine) -- Ubuntu no wget por defecto, se
# instala para el HEALTHCHECK.
RUN useradd -m -d /app -s /usr/sbin/nologin simulador \
	&& apt-get update && apt-get install -y --no-install-recommends wget \
	&& rm -rf /var/lib/apt/lists/*
COPY --from=build /build/target/banxico-simulator.jar app.jar

# Puerto SPEI, puerto ARA, puerto de la API de control HTTP (ver control/ControlServer.java).
EXPOSE 6001 6002 8089

# data/ (identidad propia + H2) y config/ (simulator.properties + minos-public-cert.pem) se
# esperan como volúmenes montados -- ver docker-compose.yml. Sin montarlos el simulador funciona
# igual (genera su propia identidad efímera dentro del contenedor) pero se pierde al recrearlo.
VOLUME ["/app/data", "/app/config"]

# El puerto de la API de control sale de control.port en config/simulator.properties (default 8089); en la
# 192.168.1.52 es 9092, y con 8089 fijo aqui el contenedor figuraba "unhealthy" aunque estuviera sano (E-9).
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
	CMD PORT="$(sed -n 's/^[[:space:]]*control\.port[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' /app/config/simulator.properties 2>/dev/null | tail -1)"; \
	wget -q -O - "http://localhost:${PORT:-8089}/health" | grep -q '"ok"' || exit 1

USER simulador
ENTRYPOINT ["java", "-jar", "app.jar"]
