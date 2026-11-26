FROM node:22-bookworm-slim AS builder
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig.base.json tsconfig.json tsconfig.server.json vite.config.ts index.html ./
COPY shared ./shared
COPY server ./server
COPY src ./src
COPY fixtures ./fixtures
RUN npm run build

FROM node:22-bookworm-slim AS runner
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg ca-certificates \
  && rm -rf /var/lib/apt/lists/*
WORKDIR /app
ENV NODE_ENV=production
ENV HOST=0.0.0.0
ENV PORT=3001
ENV DATA_DIR=/data
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY --from=builder /app/dist ./dist
COPY --from=builder /app/dist-server ./dist-server
COPY --from=builder /app/fixtures ./fixtures
RUN mkdir -p /data
VOLUME ["/data"]
EXPOSE 3001
CMD ["node", "dist-server/server/index.js"]
