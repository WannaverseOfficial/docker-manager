// @ts-check
import { writeFileSync } from "node:fs";
import { defineConfig } from "astro/config";
import starlight from "@astrojs/starlight";
import { satteri } from "@astrojs/markdown-satteri";
import baseLinks from "./src/plugins/base-links.mjs";
import { clarityScript } from "./src/analytics.mjs";

// Where the site is published. Leave SITE_DOMAIN empty for https://wannaverseofficial.github.io/docker-manager/.
// To move to a custom domain, set it (e.g. "docker-manager.example.com") and point the domain's DNS at GitHub Pages.
const CUSTOM_DOMAIN = process.env.SITE_DOMAIN ?? "";

const site = CUSTOM_DOMAIN ? `https://${CUSTOM_DOMAIN}` : "https://wannaverseofficial.github.io";
const base = CUSTOM_DOMAIN ? "/" : "/docker-manager";
const REPO = "https://github.com/WannaverseOfficial/docker-manager";

/** Writes the CNAME file GitHub Pages needs for a custom domain. */
const cname = {
  name: "cname",
  hooks: {
    "astro:build:done": ({ dir }) => {
      if (CUSTOM_DOMAIN) writeFileSync(new URL("CNAME", dir), CUSTOM_DOMAIN + "\n");
    },
  },
};

export default defineConfig({
  site,
  base,
  trailingSlash: "always",
  markdown: { processor: satteri({ mdastPlugins: [baseLinks(base)] }) },
  integrations: [
    starlight({
      title: "Docker Manager",
      description:
        "A free, open-source, self-hosted web interface for Docker: containers, Compose stacks, HTTPS ingress, DNS, databases, observability, a private registry and security scanning.",
      favicon: "/favicon.svg",
      logo: { src: "./src/assets/logo.svg", alt: "" },
      head: clarityScript ? [{ tag: "script", content: clarityScript }] : [],
      customCss: ["@fontsource-variable/inter", "@fontsource-variable/jetbrains-mono", "./src/styles/theme.css"],
      social: [{ icon: "github", label: "GitHub", href: REPO }],
      editLink: { baseUrl: `${REPO}/edit/main/site/` },
      lastUpdated: false,
      sidebar: [
        {
          label: "Start here",
          items: [
            { label: "What is Docker Manager?", slug: "docs" },
            { slug: "docs/start/install" },
            { slug: "docs/start/first-steps" },
            { slug: "docs/start/update" },
          ],
        },
        {
          label: "Run",
          items: [
            { slug: "docs/run/containers" },
            { slug: "docs/run/stacks" },
            { slug: "docs/run/templates" },
            { slug: "docs/run/images-volumes-networks" },
          ],
        },
        {
          label: "Deploy and publish",
          items: [
            { slug: "docs/deploy/git-pipelines" },
            { slug: "docs/deploy/rollouts" },
            { slug: "docs/deploy/ingress" },
            { slug: "docs/deploy/dns" },
          ],
        },
        {
          label: "Data",
          items: [{ slug: "docs/data/databases" }, { slug: "docs/data/registry" }],
        },
        {
          label: "Watch and secure",
          items: [
            { slug: "docs/watch/health" },
            { slug: "docs/watch/observability" },
            { slug: "docs/watch/security" },
            { slug: "docs/watch/policies" },
          ],
        },
        {
          label: "Administer",
          items: [
            { slug: "docs/admin/hosts" },
            { slug: "docs/admin/users" },
            { slug: "docs/admin/history" },
            { slug: "docs/admin/settings" },
            { slug: "docs/admin/configuration-as-code" },
          ],
        },
        {
          label: "Reference",
          items: [
            { slug: "docs/reference/configuration-file" },
            { slug: "docs/reference/environment" },
            { slug: "docs/reference/troubleshooting" },
            { slug: "docs/reference/faq" },
          ],
        },
      ],
    }),
    cname,
  ],
});
