FROM node:22-alpine

WORKDIR /app

COPY package*.json ./
RUN npm ci --omit=dev

COPY tsconfig.json ./
COPY src ./src
COPY public ./public
RUN npm install typescript tsx --no-save && npx tsc

EXPOSE 3000

CMD ["node", "dist/index.js"]
