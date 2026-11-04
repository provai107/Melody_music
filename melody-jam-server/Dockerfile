FROM node:20-alpine

WORKDIR /app

ENV NODE_ENV=production
ENV PORT=8080

# Install dependencies first for layer caching
COPY package*.json ./
RUN npm ci --only=production --ignore-scripts

# Copy source code
COPY . .

EXPOSE 8080

# Run as non-root user
USER node

CMD ["node", "server.js"]
