FROM node:20-alpine AS builder
WORKDIR /app

COPY package*.json ./
RUN npm install

COPY . .
RUN npm run build

FROM node:20-alpine AS runner
WORKDIR /app

COPY package*.json ./
RUN npm install --production

COPY --from=builder /app/dist ./dist
COPY --from=builder /app/uploads ./uploads
# Root CA for verifying the managed Postgres TLS chain. Without it the app
# fails closed rather than connecting unverified.
COPY --from=builder /app/certs ./certs

ENV NODE_ENV=production
EXPOSE 3000
CMD ["npm", "run", "start:prod"]
