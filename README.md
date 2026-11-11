<img width="288" alt="wannaverse logo" src="https://github.com/user-attachments/assets/7623d002-539b-42cb-b794-645b9fac471f" />

# Wannaverse Docker Manager (WDM)
WDM is a free, open-source, self-hosted web interface for managing Docker environments.
Built for tech enthusiasts, software developers, and small businesses who want powerful Docker management without subscriptions, licensing fees, or feature gates.

No paid tiers. No hidden costs. Just Docker management done right.

## 🧩 Features
* Unified management dashboard

  View running containers, host details, and real-time system metrics with intuitive graphs.


* Container lifecycle management

  Start, stop, restart, inspect, and manage Docker containers from the UI.


* Template-based container creation

  Deploy containers quickly using a curated set of preconfigured templates.


* Full Docker resource visibility

  Manage containers, networks, images, and volumes in one place.


* Built-in ingress & reverse proxy

  Nginx-based ingress with automatic TLS certificate generation and renewal.


* Git-powered workflows

  Manage containers via Git repositories with full version control support.


* Drift detection & rollbacks

  Detect configuration drift and safely roll back containers when needed.


* Health checks & monitoring

  Track container health and receive alerts when issues occur.


* Email notifications

  Get notified about deployments, container status, security events, and system alerts.
  Bring your own SMTP server and view delivery logs.


* Image security policies

  Allow or deny container images based on defined rules.


* Audit logging

  Maintain a detailed history of user actions and system changes.


* Multi-user support

  Multiple accounts with fine-grained roles and permissions.


* Multi-registry support

  Works with Docker Hub, AWS ECR, Google Container Registry, and Azure Container Registry.


* Single-container deployment

  Fully self-contained and deployable as one Docker container.

## 🚀 Running Wannaverse Docker Manager

The recommended approach is a `docker-compose.yml`:

```yaml
services:
  docker-manager:
    image: wannaverse/docker-manager:latest
    container_name: docker-manager
    pull_policy: always
    restart: unless-stopped
    ports:
      - "8080:8080"
    volumes:
      # Lets Docker Manager control this host's Docker
      - /var/run/docker.sock:/var/run/docker.sock
      # Database, and the secrets generated on first start
      - docker_manager_data:/app/database
      # Compose stacks and git checkouts. Keep the same path on both sides (see below)
      - /opt/docker-manager/stacks:/opt/docker-manager/stacks
      - /opt/docker-manager/git-repos:/opt/docker-manager/git-repos
    environment:
      # The address you open Docker Manager at; used for webhook URLs and email links
      - BASE_URL=http://your-domain.com
      # Optional: times in emails and logs (default UTC)
      # - TZ=Europe/London

volumes:
  docker_manager_data:
```

The image already has a health check, so none is needed in the compose file.

Alternatively, for a quick try:
```
docker run -d -p 8080:8080 -v /var/run/docker.sock:/var/run/docker.sock wannaverse/docker-manager
```

The Docker host whose socket is mounted is added automatically on first start.

### Secrets

Strong random secrets for sign-in tokens and for encrypting stored credentials are generated on first start and kept
in the data volume (`/app/database/.secrets.properties`). To manage them yourself, set `JWT_ACCESS_SECRET`,
`JWT_REFRESH_SECRET` and `ENCRYPTION_KEY` to random values of at least 32 characters, for example from
`openssl rand -base64 48`.

Never reuse the example secrets that older versions of this README showed: they are public. Docker Manager replaces
public or weak JWT secrets with generated ones automatically.

### Stacks and git folders

Each stack's `compose.yaml` and `.env` are stored in `STACKS_DIR` (default `/opt/docker-manager/stacks`), and git
repositories are checked out to `GIT_CLONE_DIR` (default `/opt/docker-manager/git-repos`). Docker resolves relative
bind mounts in a compose file (like `./data:/data`) on the **host**, so mount both folders at the same path on the
host and in the container, as in the example above.

Stacks you import keep running from their original folder, so their relative paths still point at the same data.

### Behind a reverse proxy

* Put Docker Manager behind HTTPS before exposing it to the internet, and consider publishing its port only to the
  proxy (`127.0.0.1:8080:8080`).
* The proxy must send `X-Forwarded-For` and `X-Forwarded-Proto`. They are trusted from private addresses (Docker
  networks, LAN); for a proxy on a public address set `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` to a regex matching it.
  Otherwise every visitor appears to come from the proxy, which shares one sign-in rate limit.
* Volume uploads can be up to 100 MB per file; raise the proxy's body size limit to match (nginx defaults to 1 MB:
  `client_max_body_size 200m;`).
* Remote Docker hosts are added as `tcp://host:2375`. That port gives full control of the host without a password,
  so only reach it over a private network, VPN or SSH tunnel.

### Settings

| Variable | Default | Meaning |
| --- | --- | --- |
| `BASE_URL` | `http://localhost:8080` | Public address, used for webhook URLs and email links |
| `STACKS_DIR` | `/opt/docker-manager/stacks` | Where stack files are kept |
| `GIT_CLONE_DIR` | `/opt/docker-manager/git-repos` | Where git repositories are checked out |
| `TZ` | `UTC` | Time zone for emails and logs |
| `JWT_ACCESS_SECRET`, `JWT_REFRESH_SECRET`, `ENCRYPTION_KEY` | generated | See Secrets |
| `JWT_ACCESS_EXP`, `JWT_REFRESH_EXP` | 15 minutes, 7 days | Session lengths in milliseconds |
| `CORS_ALLOWED_ORIGINS` | none | Only needed to run the UI from another origin during development |
| `LOG_LEVEL` | `INFO` | Server log level |

### Upgrading from 1.x

* **Back up first:** copy the `docker_manager_data` volume. On its first start 2.0 also keeps a copy of the database
  next to it (`data.db.before-2.0-migration`) before adapting its tables.
* **`ENCRYPTION_KEY`:** registry passwords, git tokens and the SMTP password are encrypted with it.
  * If your 1.x compose file had the example value from the old README, or no key at all, you can drop it: 2.0 still
    reads credentials saved with those, and uses a strong generated key for anything saved from now on.
  * If you set **your own** value, keep that exact line in the new compose file, or those credentials can't be read
    and have to be entered again.
* **Folders:** add the two `/opt/docker-manager/...` mounts from the example above. Without them stacks are kept
  inside the container and relative paths in compose files point at empty folders.
* Everyone signs in again once: sign-in tokens are now derived differently.
* Compose deployments made with 1.x were never saved anywhere; they show up under **Stacks** as "not managed here".
  Use **Find the compose file** on the stack page, or **Import all** on the Stacks page, to manage them. Importing
  never restarts anything, and an imported stack deploys without removing services it doesn't know about.
* Git repositories that deploy a Dockerfile now also run the built image. Check their container settings.
* Creating and editing users, groups, git repositories, pipelines and compose files is now limited to administrators.

### Docker Manager manages itself carefully

Docker Manager won't stop, remove, replace or disconnect its own container, and won't deploy or stop its own compose
project: compose runs inside it, so it would go offline halfway with nothing to bring it back. Update it on the
server with `docker compose pull && docker compose up -d`.

## 🔐 Logging In
Username: `admin`

Password: printed to the container logs on first start. You choose your own on first sign-in.

```
docker logs docker-manager
```

## 🛠️ Development

The server is Spring Boot (Java 25) in `src/`; the UI is a Next.js static export in `frontend/`.

```
# Server on :8099, allowing the dev UI's origin
SERVER_PORT=8099 CORS_ALLOWED_ORIGINS=http://localhost:3099 ./gradlew bootRun

# UI on :3099 (reads NEXT_PUBLIC_API_BASE from frontend/.env.development)
cd frontend && npm install && npm run dev
```

`npm run build` in `frontend/` writes `frontend/out`, which `./gradlew bootJar` bundles into the jar. The Docker image
does both.

## 🖼️ Screenshot
<img width="1000" alt="image" src="https://github.com/user-attachments/assets/75d668d6-2f5b-495d-bc78-5cda425999a2" />

## 📄 License
MIT LICENSE. See [LICENSE](./LICENSE) for details.

## 🙌 Contributing
Contributions are welcome!

Feel free to submit pull requests, suggest features, or open issues if you encounter any problems.
