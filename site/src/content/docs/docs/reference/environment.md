---
title: Environment variables
description: Every environment variable Docker Manager reads, with its default.
---

Set these in the `environment:` section of Docker Manager's compose file. None are required: the defaults suit the
[install example](/docs/start/install/).

## General

| Variable | Default | Meaning |
| --- | --- | --- |
| `BASE_URL` | `http://localhost:8080` | The address you open Docker Manager at. Used for webhook URLs and links in emails. |
| `TZ` | `UTC` | Time zone for emails and logs, for example `Europe/London`. |
| `LOG_LEVEL` | `INFO` | Server log level (`DEBUG`, `INFO`, `WARN`, `ERROR`). |
| `API_DOCS_ENABLED` | `false` | Serves the OpenAPI description (`/v3/api-docs`) and Swagger UI (`/swagger-ui`). They're public when on, so they're off unless you ask. |
| `CORS_ALLOWED_ORIGINS` | none | Only needed to run the UI from another origin during development. |

## Folders

| Variable | Default in the image | Meaning |
| --- | --- | --- |
| `STACKS_DIR` | `/opt/docker-manager/stacks` | Where stack files are kept. Mount it at the same path on the host. |
| `GIT_CLONE_DIR` | `/opt/docker-manager/git-repos` | Where git repositories are checked out. Mount it at the same path on the host. |
| `APP_DATA_DIR` | `/app/database` | Where the generated secrets are kept, next to the database. |

See [why the folders use the same path on both sides](/docs/start/install/#why-the-stack-and-git-folders-use-the-same-path-on-both-sides).

## Secrets and sign-in

| Variable | Default | Meaning |
| --- | --- | --- |
| `JWT_ACCESS_SECRET` | generated | Signs sign-in tokens. At least 32 characters. |
| `JWT_REFRESH_SECRET` | generated | Signs the tokens that keep you signed in. At least 32 characters. |
| `ENCRYPTION_KEY` | generated | Encrypts stored credentials (registry passwords, git tokens, SSH keys, the SMTP password). At least 32 characters. |
| `JWT_ACCESS_EXP` | `900000` (15 minutes) | How long a sign-in token lasts, in milliseconds. |
| `JWT_REFRESH_EXP` | `604800000` (7 days) | How long you stay signed in without signing in again, in milliseconds. |
| `DM_RESET_PASSWORD` | none | A user name. At startup that user gets a new temporary password, printed in the log, for when nobody else can reset it. Remove it afterwards. See [Troubleshooting](/docs/reference/troubleshooting/#signing-in). |

The secrets are generated on first start and stored in `/app/database/.secrets.properties`. To manage them
yourself, set each to a random value, for example from `openssl rand -base64 48`. Weak secrets, and the public
example values from older versions' README, are replaced with generated ones.

:::caution
If you set your own `ENCRYPTION_KEY` and later change or remove it, the credentials saved with it can't be read and
have to be entered again.
:::

## Configuration as code

| Variable | Default | Meaning |
| --- | --- | --- |
| `DM_CONFIG_FILE` | none | A [configuration file](/docs/admin/configuration-as-code/) to apply at startup and whenever it changes. Without it, use the Configuration page. |
| `DM_CONFIG_RECONCILE_SECONDS` | `300` | How often managed things removed by other means are put back. `0` turns it off. |

## Health monitoring

| Variable | Default | Meaning |
| --- | --- | --- |
| `DOCKER_HEALTH_MONITOR_ENABLED` | `true` | Turns [health monitoring](/docs/watch/health/) on or off. |
| `DOCKER_HEALTH_MONITOR_INTERVAL` | `30000` (30 seconds) | How often containers are checked, in milliseconds. |
| `DOCKER_HEALTH_RESTART_THRESHOLD` | `3` | How many restarts within the window count as a restart loop. |
| `DOCKER_HEALTH_RESTART_WINDOW` | `300000` (5 minutes) | The restart loop window, in milliseconds. |
| `DOCKER_HEALTH_NOTIFICATION_COOLDOWN` | `3600000` (1 hour) | The least time between two emails about the same problem, in milliseconds. |

## Network

| Variable | Default | Meaning |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | The port the web interface listens on inside the container. The image's health check uses 8080, so prefer changing the published port (`"9000:8080"`) instead. |
| `DNS_PORT` | `1053` | The port the [DNS server](/docs/deploy/dns/) listens on inside the container. Publish it as port 53. |
| `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` | private addresses | A regular expression of reverse proxies whose `X-Forwarded-For` and `X-Forwarded-Proto` headers are trusted. Only needed for a proxy on a public address. See [Behind a reverse proxy](/docs/start/install/#behind-a-reverse-proxy). |

## Java and the database

These are set in the image. You rarely need to change them.

| Variable | Default in the image | Meaning |
| --- | --- | --- |
| `JAVA_OPTS` | `-Xms128m -Xmx512m -XX:+UseSerialGC` | Options for the Java runtime, such as its memory limit. |
| `SPRING_DATASOURCE_URL` | `jdbc:sqlite:/app/database/data.db?...` | Where the SQLite database is. |
