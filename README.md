<img width="288" alt="wannaverse logo" src="https://github.com/user-attachments/assets/7623d002-539b-42cb-b794-645b9fac471f" />

# Wannaverse Docker Manager
Wannaverse Docker Manager is a free, open-source, self-hosted web interface for managing Docker environments.
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

With Docker installed, run:
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
