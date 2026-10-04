FROM node:22-alpine AS build
WORKDIR /web
RUN corepack enable
COPY poker-web/package.json poker-web/pnpm-lock.yaml poker-web/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY poker-web/ ./
RUN pnpm build

FROM caddy:2-alpine
COPY deploy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /web/dist /srv
EXPOSE 80 443 443/udp
