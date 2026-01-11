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

The recommended approach is using a `docker-compose.yml`

Please ensure to change the SECRETS in the file. To generate values you can run the command:

```
openssl rand -base64 48 | tr -dc 'A-Za-z0-9!@#$%^&*()_+-=' | head -c 32 ; echo
```

docker-compose.yml
```
services:
  docker-manager:
    image: wannaverse/docker-manager:latest
    container_name: docker-manager
    pull_policy: always
    restart: unless-stopped
    ports:
      - "8080:8080"
    volumes:
      # Mount Docker socket for container management
      - /var/run/docker.sock:/var/run/docker.sock
      # Persist database
      - docker_manager_data:/app/database
      # Persist git repositories
      - docker_manager_repos:/app/git-repos
    environment:
      # Java options
      - JAVA_OPTS=-Xms256m -Xmx512m
      # Security - CHANGE THESE IN PRODUCTION
      - JWT_ACCESS_SECRET=bI4pEE+5oVq9gkWyy/plIsRvaZ8ghj54access
      - JWT_REFRESH_SECRET=bI4pEE+5oVq9gkWyy/plIsRvaZ8ghj54refresh
      - ENCRYPTION_KEY=bI4pEE+5oVq9gkWyy/plIsRvaZ8ghj54
      # Optional: Increase token expiration
      # - JWT_ACCESS_EXP=900000
      # - JWT_REFRESH_EXP=604800000
      # Public URL for webhooks - set to your public domain
      - BASE_URL=http://your-domain.com
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/api/auth/login"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s

volumes:
  docker_manager_data:
  docker_manager_repos:
```

Alternatively, for testing, run:
```
docker run -d \
  -p 8080:8080 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  wannaverse/docker-manager
```
By default, the application runs on port 8080.
To change it, modify the host port (for example: 4000:8080).

## 🔐 Logging In
Username: `admin`

Password: Printed to the container logs on first startup

Retrieve it with:
```
docker logs docker-manager
```

## 🖼️ Screenshot
<img width="1000" alt="image" src="https://github.com/user-attachments/assets/75d668d6-2f5b-495d-bc78-5cda425999a2" />

## 📄 License
MIT LICENSE. See [LICENSE](./LICENSE) for details.

## 🙌 Contributing
Contributions are welcome!

Feel free to submit pull requests, suggest features, or open issues if you encounter any problems.
