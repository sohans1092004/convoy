FROM node:22.23.4-alpine
WORKDIR /app
COPY tools/razorpay-fake/server.mjs ./
USER node
EXPOSE 9090
CMD ["node", "server.mjs"]
