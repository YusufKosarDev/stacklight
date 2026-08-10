import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "Stacklight",
  description: "Error ingestion and triage",
};

/*
 * Every route renders per request.
 *
 * Declared here rather than per page because the shell reads the database for
 * its nav counts. Without it Next would try to prerender /how-grouping-works at
 * build time, and CI builds with no DATABASE_URL on purpose. The cost is that
 * the one static page becomes a per-request render.
 */
export const dynamic = "force-dynamic";

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html
      lang="en"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="min-h-full font-sans text-ink">{children}</body>
    </html>
  );
}
