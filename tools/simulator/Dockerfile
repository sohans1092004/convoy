# Public demo simulated dealers (P6.B): tools/simulator/demo-bots.mjs. Build context: repo root.
FROM node:22.23.4-alpine
WORKDIR /app
COPY tools/simulator/dealer-sim.mjs tools/simulator/demo-bots.mjs tools/simulator/dealers.json ./
USER node
CMD ["node", "demo-bots.mjs"]
