import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  // The app is a single client-side page (no server features), so it is exported as plain static files.
  // The production image then only needs a tiny nginx instead of a full Node.js runtime.
  output: "export",
  images: { unoptimized: true },
};

export default nextConfig;
