# Backup App server image.
# Pinned to the Node 22 line the server is developed against (crypto.timingSafeEqual,
# fs.statfsSync are both available here). Alpine keeps the image small.
FROM node:22-alpine

WORKDIR /app

# Install deps first, as their own layer, so source edits don't bust the npm cache.
# Only production deps are needed (express + multer); there are no dev deps.
COPY package.json package-lock.json ./
RUN npm ci --omit=dev

# App source. public/ (APK + index.html) and version.json must be in the image
# because the server serves them directly; data/ is intentionally NOT copied —
# it is a runtime volume (see .dockerignore and compose).
COPY . .

# Personal-data backups live here and must survive container recreation.
# Pre-create it owned by the unprivileged runtime user so a named volume
# inherits the right ownership on first mount.
RUN mkdir -p /app/data && chown -R node:node /app/data

ENV PORT=4425
# BACKUP_APP_KEY is deliberately NOT set here — the server refuses to start
# without it, which is the behaviour we want. Supply it at run time
# (compose env / `docker run -e`), never bake the secret into the image.

EXPOSE 4425

USER node

# Liveness: the unauthenticated /api/version endpoint returns 200 when the
# server is serving. busybox wget ships in the alpine base, so no extra deps.
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${PORT}/api/version" || exit 1

CMD ["node", "server.js"]
