import type { NextConfig } from "next";

const backendApiBaseUrl = process.env.BACKEND_API_BASE_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  allowedDevOrigins: ["127.0.0.1", "localhost", "192.168.108.239"],
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: `${backendApiBaseUrl}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
