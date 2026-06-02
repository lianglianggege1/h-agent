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

## Getting Started

### Backend

```bash
cd backend
./mvnw spring-boot:run
# or: mvn spring-boot:run
```

The backend runs on http://localhost:8080

### Frontend

```bash
cd frontend
npm install
npm run dev
```

The frontend runs on http://localhost:3000 and proxies `/api/*` requests to the backend.

## Development

- Backend API: http://localhost:8080
- Frontend: http://localhost:3000
- API proxy: Frontend proxies `/api/*` to backend

# 效率工具
npx @colbymchenry/codegraph