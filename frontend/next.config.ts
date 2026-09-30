import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  // Self-contained server bundle used by the production Docker image.
  output: "standalone",
};

export default nextConfig;
