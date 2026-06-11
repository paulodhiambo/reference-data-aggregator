# Reference Data Aggregator Kubernetes Deployment & Troubleshooting Guide

This guide describes how to deploy, verify, and troubleshoot the **Reference Data Aggregator** on a Kubernetes cluster.

---

## Part 1: Deployment Guide

### Prerequisites
1. A running Kubernetes cluster (Minikube, Kind, GKE, EKS, AKS).
2. `kubectl` CLI configured to communicate with your cluster.
3. Docker or another container builder installed.
4. (Optional) Local docker registry or Minikube tunnel enabled.

### 1. Build and Prepare the Docker Image
Reference Data Aggregator includes a multi-stage [Dockerfile](../Dockerfile) that packages Java 21 / Spring Boot into a minimal alpine JRE image.

```bash
docker build -t loopdfs/reference-data-aggregator:1.2.0 .
```

*Note: If running inside Minikube, point your terminal context to minikube's Docker daemon first:*
```bash
eval $(minikube docker-env)
docker build -t loopdfs/reference-data-aggregator:1.2.0 .
```

### 2. Apply Configuration & Secrets
Apply the Secret [k8s/secret.yaml](../k8s/secret.yaml) and the ConfigMap [k8s/configmap.yaml](../k8s/configmap.yaml):

```bash
kubectl apply -f k8s/secret.yaml
kubectl apply -f k8s/configmap.yaml
```

### 3. Deploy StatefulSet, Service, HPA, and PDB
The main workload manifests are:

```bash
kubectl apply -f k8s/service.yaml
kubectl apply -f k8s/hpa.yaml
kubectl apply -f k8s/pdb.yaml
kubectl apply -f k8s/statefulset.yaml
```

This will deploy:
* **StatefulSet (reference-data-aggregator):** A 3-replica set where each pod gets its own Persistent Volume Claim (PVC) to preserve the local cache snapshot JSON, protecting against SOAP outages on container rescheduling.
* **Service (reference-data-aggregator):** A headless/ClusterIP service exposing port `80` to route internal traffic to container port `8080`.
* **HorizontalPodAutoscaler (reference-data-aggregator-hpa):** Autoscales replicas from 3 to 10 based on CPU (70%) and Memory (80%) thresholds.
* **PodDisruptionBudget (reference-data-aggregator-pdb):** Restricts voluntary disruptions to ensure at least 2 replicas remain available during upgrades or maintenance.

### 4. Automated Deployment script
Alternatively, you can run the helper script [deploy.sh](../deploy.sh) in the root of the project to orchestrate all the steps above automatically:

```bash
bash deploy.sh
```

---

## Part 2: Verification Guide

To verify that the application has deployed successfully and is ready to serve traffic:

### 1. Check Rollout Status
Verify that the pods are running and ready:
```bash
kubectl rollout status statefulset/reference-data-aggregator
```

### 2. Validate Pod Status & PV Claims
Ensure that 3 replicas are running and their PVC volume mounts are bound:
```bash
kubectl get pods -l app=reference-data-aggregator
kubectl get pvc -l app=reference-data-aggregator
```

### 3. Verify Health Probes (Actuator)
Check the actuator endpoints directly. You can port-forward to a pod (e.g. `reference-data-aggregator-0`):
```bash
kubectl port-forward pod/reference-data-aggregator-0 8080:8080
```
Then query the health status:
```bash
curl -s http://localhost:8080/actuator/health
```

The response should indicate status `UP` for all components, indicating the snapshot has successfully loaded:
```json
{
  "status": "UP",
  "components": {
    "readinessState": { "status": "UP" },
    "referenceData": {
      "status": "UP",
      "details": {
        "source": "LIVE",
        "countries": 246,
        "stale": false
      }
    }
  }
}
```

---

## Part 3: Troubleshooting Guide

If the application is failing probes, returning errors, or behaving abnormally, follow these troubleshooting routines:

### 1. The Pod is stuck in `Pending`
This usually implies scheduler constraints or volume mapping issues.
* **Inspect Pod Events:**
  ```bash
  kubectl describe pod reference-data-aggregator-0
  ```
* **Verify Storage Class:** If PVC creation fails, ensure your cluster has a default dynamic volume provisioner (e.g., standard GP2/GP3 in AWS, hostpath/standard in Minikube):
  ```bash
  kubectl get storageclass
  kubectl get pvc
  ```

### 2. The Pod is in `CrashLoopBackOff`
* **Examine Logs:** Get the container's standard error/stdout logs:
  ```bash
  kubectl logs statefulset/reference-data-aggregator --all-containers --tail=100
  ```
* **Check Previous Crash Logs:** If the container crashed, look at the logs of the terminated container instance:
  ```bash
  kubectl logs pod/reference-data-aggregator-0 -p
  ```
* **Check Resource Limits:** If the application is killed with code `137`, it is being terminated by the Out-Of-Memory (OOM) killer. Verify the container has sufficient memory. Increase the limit in `statefulset.yaml` under `limits.memory` or tune JVM parameters `-XX:MaxRAMPercentage`.

### 3. Service is Running but Clients Receive 503 Service Unavailable
A `503` means the internal cache hasn't booted up yet, or the data is completely unavailable (outages longer than 48 hours with no local disk/baseline copy).
* **Verify Cache Startup State:**
  Look at the logs for messages like `Fetching snapshot from SOAP provider...`.
  * If you see SOAP failures:
    ```
    WARN  - Failed to load snapshot from SOAP: Connect timed out.
    ```
  * Confirm that the pod can resolve and access the external SOAP endpoint:
    ```bash
    kubectl exec -it reference-data-aggregator-0 -- curl -I "http://webservices.oorsprong.org/websamples.countryinfo/CountryInfoService.wso?WSDL"
    ```
  * If DNS fails, inspect coreDNS/networking inside the cluster.

### 4. Data Staleness & Fallback Audits
When the SOAP upstream goes down, the application will fallback to local disk storage (`/data/snapshot/reference_data_aggregator_snapshot.json`) or classpath baseline.
* **Check Snapshot Status Gauge:**
  Check the health indicator tags. Under `referenceData.details.source`, look for:
  * `LIVE`: Normal operating state.
  * `DISK_RESTORE`: Recovered from local PVC storage during restart while SOAP was offline.
  * `BASELINE_FALLBACK`: Recovered from packaged JSON baseline during startup (SOAP down and no disk cache found).
* **Confirm Staleness Indicators:**
  When source is `BASELINE_FALLBACK` or cache has not refreshed for >48 hours, API responses will carry the header `X-RDAS-Stale: true` and the JSON response field `"stale": true`. This signals downstream clients that data is stale but serviceable.

### 5. Circuit Breaker / Rate Limiting (Resilience4j)
If requests are failing with "Circuit Breaker is OPEN" or rate limit exceptions:
* **Check Circuit Breaker status:**
  ```bash
  curl -s http://localhost:8080/actuator/health | grep circuitbreaker
  ```
* **Reset Circuit Breaker (or Force Cache Refresh):**
  If the upstream is recovered and you want to bypass the hourly retry scheduling or wait times, you can trigger a manual cache refresh by restarting the pods to flush current circuit breaker states:
  ```bash
  kubectl rollout restart statefulset/reference-data-aggregator
  ```
