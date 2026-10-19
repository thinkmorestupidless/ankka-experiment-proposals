---
title: Deploy on ankka
description: Build the image, create the secret the service reads, apply the service descriptor to an ankka project and expose it, on a local kind installation or any other.
kind: guide
related: [reference/configuration.md, get-started/run-locally.md]
---

# Deploy on ankka

The service deploys like any ankka service: an image, a descriptor, and a secret.

## Build the image

```bash
sbt Docker/publishLocal
```

This builds `ankka-experiment-proposals:<version>` and `:latest` into the local Docker daemon. For a
local kind installation, load it into the cluster:

```bash
kind load docker-image ankka-experiment-proposals:latest --name ankka
```

For a remote cluster, set `DOCKER_REPOSITORY` and run `sbt Docker/publish`.

## Create the secret

The descriptor reads the API token and the model key from a Kubernetes Secret named `edp-config`, which
you create in the project's namespace:

```bash
kubectl -n <project namespace> create secret generic edp-config \
  --from-literal=api-token=<token> \
  --from-literal=anthropic-api-key=<key>
```

## Apply the descriptor

```json title="service.json"
{
  "name": "ankka-experiment-proposals",
  "service": {
    "image": "ankka-experiment-proposals:latest",
    "runtime": "0.12.0",
    "minInstances": 1,
    "env": [
      { "name": "EDP_TIER", "value": "live" },
      { "name": "EDP_MODEL", "value": "claude-opus-5-5" },
      { "name": "EDP_EMBEDDING_MODEL", "value": "BAAI/bge-small-en-v1.5" },
      { "name": "EDP_REVIEWERS_FILE", "value": "/ankka/config/reviewers.json" },
      { "name": "EDP_CONTACT_EMAIL", "value": "change-me@example.org" },
      { "name": "EDP_API_TOKEN", "secretKeyRef": { "name": "edp-config", "key": "api-token" } },
      { "name": "ANTHROPIC_API_KEY", "secretKeyRef": { "name": "edp-config", "key": "anthropic-api-key" } }
    ]
  }
}
```

```bash
ankka services apply -f service.json
ankka services expose ankka-experiment-proposals
```

`runtime` must match the ankka version in `build.sbt`; the two move together on an upgrade. The platform
provisions the service's database. Set `EDP_CONTACT_EMAIL` to a real address before searching the
external indexes, which ask for one.

## Access

`/api/v1` and `/review` require the token; `/healthz` is open and read-only. Reviewer identity is a name
from the configured list, recorded on each decision; the token says who may call, not who they are.
Nothing in the application needs more than one instance.

## After deploying

Run [the walkthrough](../get-started/walkthrough.md) against the exposed hostname with
`--base https://<hostname>`. In the live tier the first run calls the model at every stage, so it takes
minutes rather than seconds.
