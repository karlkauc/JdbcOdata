# Kubernetes

The Helm chart in `deploy/helm/jdbcodata` runs several replicas behind one service. It needs a PostgreSQL database for metadata, which you provide. The chart deliberately does not include one, because backup and failover of that database decide how available the service is.

## Install

Create a Secret first. The chart only references it, so no password ends up in a values file:

```bash
kubectl create secret generic jdbcodata \
  --from-literal=JDBCODATA_MASTER_KEY="$(openssl rand -base64 32)" \
  --from-literal=JDBCODATA_ADMIN_PASSWORD='…' \
  --from-literal=JDBCODATA_META_PASSWORD='…'
```

!!! warning
    Back up `JDBCODATA_MASTER_KEY`. Without it, stored database passwords and asynchronous results cannot be read. Never recreate the Secret with a new key.

Then install from a checkout of the repository:

```bash
helm install jdbcodata deploy/helm/jdbcodata \
  --set existingSecret=jdbcodata \
  --set metadata.url=jdbc:postgresql://postgres:5432/jdbcodata \
  --set ingress.enabled=true \
  --set ingress.host=odata.example.org \
  --set ingress.tlsSecret=odata-tls
```

`deploy/helm/jdbcodata/values.yaml` describes all settings. Pods run as a non-root user with a read-only root file system and no service account token.

## Replicas and limits

The chart starts two replicas. They can start at the same time, and a PodDisruptionBudget keeps at least one running during node maintenance. Sticky sessions are not needed.

Most limits apply per replica, so raising `replicaCount` multiplies the load on your databases. See [Several instances](operations.md#several-instances).

## Configuration

Everything under `config` becomes `/app/config/application.yml`, and everything under `seed` becomes the seed file:

```yaml
config:
  jdbcodata:
    odata:
      max-rows: 50000
```

Result sets are held in memory, so raise `resources.limits.memory` together with `max-rows`. The heap is `heapPercent` of the memory limit.

For single sign-on, set the OIDC settings under `config` and pass the client secret through `extraEnv` with a `secretKeyRef`.

## Oracle and other JDBC drivers

The Oracle driver is not part of the image. Copy it to `/app/lib` with an init container:

```yaml
extraVolumes:
  - name: drivers
    emptyDir: {}
extraVolumeMounts:
  - name: drivers
    mountPath: /app/lib
    readOnly: true
extraInitContainers:
  - name: oracle-driver
    image: registry.example.org/ojdbc:23
    command: ["cp", "/ojdbc11.jar", "/lib-target/"]
    volumeMounts:
      - name: drivers
        mountPath: /lib-target
```

## Monitoring

The ServiceMonitor needs `JDBCODATA_SECURITY_METRICS_TOKEN` in the Secret (at least 32 characters). ServiceMonitor and alert rules need the Prometheus Operator.

| Setting | Creates |
|---|---|
| `metrics.serviceMonitor.enabled` | ServiceMonitor that scrapes `/actuator/prometheus` with the token |
| `metrics.prometheusRule.enabled` | Alerts for replicas down, failing queries, waiting connections, response budget, heap, login attacks and lost log records |
| `metrics.dashboard.enabled` | ConfigMap with a Grafana dashboard for the Grafana sidecar |

Without the operator, use `deploy/helm/jdbcodata/dashboards/alerts.yaml` and `jdbcodata.json` directly.
