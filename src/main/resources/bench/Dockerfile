# Docker Bench for Security, built by Docker Manager on the host it checks.
# The scripts in this folder are docker/docker-bench-security at commit
# 154869da6418089decf7e1ab0cfca0e1cdfc5c49 (Apache-2.0, see LICENSE.md). The published
# docker/docker-bench-security image is from 2019 and can't talk to current Docker versions,
# so the upstream project recommends building it; this uses a current Alpine base.
FROM alpine:3.22
RUN apk add --no-cache iproute2 docker-cli dumb-init jq procps
COPY . /usr/local/bin/
WORKDIR /usr/local/bin
ENTRYPOINT ["/usr/bin/dumb-init", "/bin/sh", "docker-bench-security.sh"]
CMD [""]
