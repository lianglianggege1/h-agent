# h-agent

A full-stack project with a Spring Boot backend and a Next.js frontend.

## Project Structure

```
h-agent/
├── backend/          # Spring Boot backend (Java 17, Spring Boot 4.x)
├── frontend/        # Next.js frontend (React 19, Next.js 16)
└── CLAUDE.md         # Project instructions
```

## Prerequisites

- Java 17+
- Node.js 18+
- Maven 3.8+
- Nginx (the development gateway listens on port 8089)

## Getting Started

### Backend

```bash
cd backend
./mvnw spring-boot:run
# or: mvn spring-boot:run
```

The backend runs on http://localhost:8081.

### Frontend

```bash
cd frontend
npm install
npm run dev
```

The Next.js development server runs on http://localhost:3000.

### Development Gateway

The main development entry is Nginx. Its project configuration is
[`deploy/nginx/h-agent-dev.conf`](deploy/nginx/h-agent-dev.conf):

- `/api/*` goes directly to Spring Boot on port 8081, including SSE streams.
- All other paths go to Next.js on port 3000, including HMR connections.

After starting the backend and frontend, validate and reload the installed Nginx service:

```bash
nginx -t
nginx -s reload
```

Open http://localhost:8089 locally. Other devices on the same LAN can use
`http://<development-machine-ip>:8089`. Port 3000 remains a direct-access fallback.

## Development

- Development entry: http://localhost:8089
- Backend API: http://localhost:8081
- Next.js direct fallback: http://localhost:3000
- API routing: Nginx sends `/api/*` directly to Spring Boot

# 效率工具
npx @colbymchenry/codegraph
