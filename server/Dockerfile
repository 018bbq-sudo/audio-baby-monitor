FROM node:22-alpine
WORKDIR /app
COPY package.json pnpm-lock.yaml ./
RUN npm install --global pnpm@11.19.0 && pnpm install --prod --frozen-lockfile --ignore-scripts
COPY server.mjs sos.mjs ./
COPY sos-relay.js sos-protocol.js sos-domain.js ./
ENV PORT=8080
EXPOSE 8080
CMD ["node", "server.mjs"]

