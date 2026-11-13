# Docker Manager website

The public product page and documentation, built with [Astro](https://astro.build) and
[Starlight](https://starlight.astro.build), and published to GitHub Pages by `.github/workflows/site.yml`.

```bash
cd site
npm ci
npm run dev       # http://localhost:4321/docker-manager/
npm run build     # static site in dist/
```

- `src/pages/index.astro`: the landing page.
- `src/content/docs/docs/`: the docs, one Markdown file per page. Add new pages to the sidebar in `astro.config.mjs`.
- `src/assets/screens/`: app screenshots (1440x900) used by the docs and the landing page.
- `public/art/`: the paintings. Some are shared with the app (`frontend/public/art/`).

Write links from the site root, like `[Ingress](/docs/deploy/ingress/)`; `src/plugins/base-links.mjs` adds the base path.

## Where it's published

By default the site is built for `https://wannaverseofficial.github.io/docker-manager/`. To use a custom domain, set
the repository variable `SITE_DOMAIN` (for example `docker-manager.example.com`); the build then uses `/` as its base
and writes the `CNAME` file. Locally: `SITE_DOMAIN=docker-manager.example.com npm run build`.

## Analytics

Set the repository variable `CLARITY_ID` to a Microsoft Clarity project id to add Clarity to every page. Without it
the site has no analytics. Visits from localhost are never counted.
