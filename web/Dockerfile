# Next.js web (BFF + UI). Build context: repo root (needs packages/* and spec-tools/vectors/rules.json).
FROM node:22.23.4-alpine AS build
WORKDIR /repo
RUN corepack enable && corepack prepare pnpm@10.18.2 --activate
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml tsconfig.base.json ./
COPY packages ./packages
COPY spec-tools/vectors/rules.json spec-tools/vectors/pricing.json ./spec-tools/vectors/
COPY web/package.json ./web/package.json
COPY tools/razorpay-fake/package.json ./tools/razorpay-fake/package.json
RUN pnpm install --frozen-lockfile --filter @convoy/web...
COPY web ./web
ENV NEXT_TELEMETRY_DISABLED=1
RUN pnpm --filter @convoy/web build

FROM node:22.23.4-alpine
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
COPY --from=build /repo/web/.next/standalone ./
COPY --from=build /repo/web/.next/static ./web/.next/static
COPY --from=build /repo/web/public ./web/public
USER node
EXPOSE 3000
CMD ["node", "web/server.js"]
