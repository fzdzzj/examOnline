# =============================================================================
# examOnline 前端多阶段构建镜像（add-deployment-stack）
#   docker build -f frontend/Dockerfile -t exam-online-web:prod frontend/
#
# 阶段一（构建）：node 22 + corepack 启用与本仓 packageManager 锁定版本一致的
#   pnpm（package.json 的 "packageManager": "pnpm@10.25.0"）+ --frozen-lockfile
#   （lockfile 严格校验）+ vite build 产出 dist。
# 阶段二（运行）：nginx stable-alpine 托管 dist，TLS 终结与 /api/ 同源反代
#   见 frontend/nginx.conf；证书经挂载点注入（docker-compose.prod.yml）。
# =============================================================================
FROM node:22 AS build
WORKDIR /app

# 先装依赖再拷源码，命中时可复用依赖层缓存
COPY package.json pnpm-lock.yaml ./
RUN corepack enable && pnpm install --frozen-lockfile

COPY . .
RUN pnpm build

# ---- 运行层 ----
FROM nginx:stable-alpine AS runtime

# 默认产物目录（frontend/Dockerfile 的 build context 是 frontend/ 本身）
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/nginx.conf

# TLS 证书挂载点：宿主生成后挂到 /etc/nginx/certs/（见 docker-compose.prod.yml）
RUN mkdir -p /etc/nginx/certs

EXPOSE 80 443
CMD ["nginx", "-g", "daemon off;"]