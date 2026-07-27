FROM node:22-alpine AS build
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/index.html frontend/tsconfig.json frontend/vite.config.ts ./
COPY frontend/src src
COPY frontend/public public
COPY frontend/scripts scripts
COPY contracts/openapi/api.yaml /app/contracts/openapi/api.yaml
RUN npm run build

FROM nginx:1.29-alpine
COPY --from=build /app/frontend/dist /usr/share/nginx/html
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
