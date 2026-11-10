import type { Metadata, Viewport } from "next";
import { Inter, JetBrains_Mono } from "next/font/google";
import Shell from "@/components/Shell";
import { HostProvider, SessionProvider } from "@/components/app";
import { ConfirmProvider, ToastProvider } from "@/components/ui";
import "./globals.css";

const body = Inter({ variable: "--font-body", subsets: ["latin"] });
const code = JetBrains_Mono({ variable: "--font-code", subsets: ["latin"] });

export const metadata: Metadata = {
  title: "Docker Manager",
  description: "Run, deploy and look after your Docker containers.",
  icons: { icon: "/favicon.svg" },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  themeColor: [
    { media: "(prefers-color-scheme: dark)", color: "#0b0b0c" },
    { media: "(prefers-color-scheme: light)", color: "#fafafa" },
  ],
};

// Runs before first paint so a saved light theme never flashes dark.
const THEME_SCRIPT = `try{var t=localStorage.getItem("dm.theme");if(t==="light")document.documentElement.dataset.theme="light"}catch(e){}`;

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${body.variable} ${code.variable}`} suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME_SCRIPT }} />
      </head>
      <body>
        <SessionProvider>
          <HostProvider>
            <ToastProvider>
              <ConfirmProvider>
                <Shell>{children}</Shell>
              </ConfirmProvider>
            </ToastProvider>
          </HostProvider>
        </SessionProvider>
      </body>
    </html>
  );
}
