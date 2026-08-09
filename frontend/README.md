# h-agent frontend Node 服务

该前端使用 Next.js standalone 模式构建为可独立启动的 Node.js 服务。服务同时负责：

- 提供前端 Web 页面和静态资源；
- 将浏览器访问的 `/api/*` 请求代理到后端服务；
- 支持现有的长时间 SSE 会话。

## 本地开发

```bash
npm ci
npm run dev
```

默认地址为 <http://localhost:3000>。

## 构建独立服务

```bash
npm ci
BACKEND_API_BASE_URL=http://127.0.0.1:8081 npm run package
```

构建结果位于 `dist/`。该目录已经包含运行所需的 Node 模块、前端静态资源和启动文件，不需要携带源码或再次执行 `npm install`。

可以将整个 `dist/` 目录复制到另一台具有兼容 Node.js 版本的机器，然后启动：

```bash
cd dist
PORT=3000 node start
```

浏览器访问 <http://localhost:3000>。

可用环境变量：

- `BACKEND_API_BASE_URL`：后端服务地址，默认 `http://localhost:8081`；这是构建变量，必须在执行打包命令时设置，因为 Next.js rewrites 会写入构建产物。
- `PORT`：Node Web 服务端口，默认 `3000`。
- `HOSTNAME`：监听地址，默认 `0.0.0.0`。

在源码目录中完成打包后，也可以直接执行 `npm start`，它等价于 `node dist/start`。
