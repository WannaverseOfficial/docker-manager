<img alt="Wannaverse Logo" src="logo.png" width="288"/>

# docker-manager-starter
Free open-source web management software for Docker

Command:
```
docker run -d \
  -p 8080:8080 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  wannaverse/docker-manager
```

The default username is `admin` and the password will be printed to the container logs.

```
docker logs docker-manager
```

Screenshot:
<img width="1000" alt="image" src="https://github.com/user-attachments/assets/75d668d6-2f5b-495d-bc78-5cda425999a2" />
