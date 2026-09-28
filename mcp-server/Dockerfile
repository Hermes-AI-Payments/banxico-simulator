# Servidor MCP en modo HTTP, desplegado junto a la API de control (spec 011 §"Despliegue junto a
# la API"). Quien prueba solo necesita la URL de este servicio -- no clonar el repo ni instalar Node.
# node:20-alpine publica linux/amd64 y linux/arm64, igual que la imagen del simulador.
FROM node:20-alpine
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-fund --no-audit
COPY index.js ./

ENV MCP_TRANSPORT=http \
	MCP_PORT=8090
EXPOSE 8090

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
	CMD wget -q -O - http://localhost:8090/health | grep -q '"ok"' || exit 1

USER node
ENTRYPOINT ["node", "index.js"]
