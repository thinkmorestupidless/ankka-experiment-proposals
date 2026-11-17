---
title: Deploy the interface
description: Build the interface's image and apply its descriptor beside the service, so its address serves the application and passes /backend to the service.
kind: guide
related: [deploy/deploy-on-ankka.md, get-started/interface.md]
---

# Deploy the interface

The interface is a web-hosted ankka service. It is a separate service from the backend, deployed into
the same project. Its descriptor is `web/service.json`:

```json
{
  "name": "experiment-proposals-web",
  "service": {
    "image": "experiment-proposals-web:latest",
    "hosting": "web",
    "processPort": 3000,
    "mounts": [{ "path": "/backend", "service": "ankka-experiment-proposals" }]
  }
}
```

The platform runs its proxy beside the interface's process. A request under `/backend` goes to the
service `ankka-experiment-proposals` with the prefix removed, and every other request goes to the
interface's own server. The browser reaches the service at the interface's address, so the service
need not be exposed itself.

## Build the image

```bash
just web-image      # npm run build, then docker build -t experiment-proposals-web:latest web
```

The image holds the built application and its server, with only production dependencies, and runs as
user 1000. It listens on the `PORT` the platform gives it.

## Apply it

Deploy the service first, as [Deploy on ankka](deploy-on-ankka.md) describes. Then:

```bash
kind load docker-image experiment-proposals-web:latest --name ankka   # a local kind cluster; push to a registry otherwise
ankka services apply -f web/service.json
ankka services get experiment-proposals-web
ankka services expose experiment-proposals-web
```

`ankka services get` shows the service as Ready, with its mount `/backend` and the service behind it.
The interface asks for the service's token in the browser, as it does locally.
