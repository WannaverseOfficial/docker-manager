import type { NextConfig } from "next";

// The UI is exported as static files and served by the Spring Boot server on the same port.
// In development, run the server on :8099 with CORS_ALLOWED_ORIGINS=http://localhost:3000 and set
// NEXT_PUBLIC_API_BASE=http://localhost:8099 (see .env.development).
const nextConfig: NextConfig = {
  output: "export",
  trailingSlash: true,
  images: { unoptimized: true },
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;
