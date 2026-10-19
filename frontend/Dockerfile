FROM node:22-alpine AS build
WORKDIR /app
COPY package.json ./
RUN npm install --no-audit --no-fund
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
RUN addgroup -S adpilot && adduser -S adpilot -G adpilot
COPY --from=build --chown=adpilot:adpilot /app/.next/standalone ./
COPY --from=build --chown=adpilot:adpilot /app/.next/static ./.next/static
COPY --from=build --chown=adpilot:adpilot /app/public ./public
USER adpilot
EXPOSE 3000
CMD ["node", "server.js"]
