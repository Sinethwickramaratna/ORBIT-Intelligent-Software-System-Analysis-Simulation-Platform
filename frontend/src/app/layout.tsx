import type { Metadata } from "next";
import type { ReactNode } from "react";
import "@fontsource/antonio/400.css";
import "@fontsource/source-code-pro/400.css";
import "@fontsource/source-code-pro/600.css";
import "./globals.css";

export const metadata: Metadata = {
  title: "ORBIT",
  description: "ORBIT — Intelligent Software System Analysis & Simulation Platform",
};

/** Applies the cached theme before first paint to avoid a flash; the database value is applied once loaded. */
const themeBootstrap = `try{var t=localStorage.getItem("orbit.theme");if(t){document.documentElement.setAttribute("data-theme",t)}}catch(e){}`;

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" data-theme="orbit-dark" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeBootstrap }} />
      </head>
      <body>{children}</body>
    </html>
  );
}
