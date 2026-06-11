# Part 6 – Engineering Discussion Responses

This document provides structured, professional engineering responses to the three discussion questions outlined in the NCBA Backend Engineer assignment.

---

## 🙋 Question 1: How would your solution change if the SOAP provider reduced the limit to 10 requests per minute?

### 1. Analysis of Current Load
Under our current decoupled snapshot architecture, the SOAP provider is **never** on the hot path of client requests. Replicas serve all queries directly from an in-memory snapshot. 
* A full snapshot refresh requires exactly **4 SOAP requests** (one for the consolidated country/language list, and three for continent, currency, and language name joins).
* Twice-daily background refreshes mean each pod makes **8 requests per day**.
* With a budget of 10 requests/minute, the provider allows **14,400 requests/day**. In a steady state of 3 replicas, our cluster generates only **24 requests/day (0.16% of the budget)**. Therefore, the core snapshot architecture requires **no fundamental redesign**.

### 2. Safeguards and Enhancements for a 10 req/min Limit
To protect against rate-limit bursts during deployment rollouts, auto-scaling events, or simultaneous replica restarts:

* **ShedLock / Distributed Scheduling:**
  We would introduce distributed lock coordination (such as ShedLock, Spring Integration, or Kubernetes Leases) to ensure that **exactly one pod** in the cluster runs the refresh cron task. The scheduled pod fetches the candidate snapshot, writes it to a shared storage layer (e.g. AWS S3, a shared Redis instance, or ReadWriteMany PVC), and other pods reload it. This keeps cluster SOAP traffic locked to exactly 4 requests per refresh cycle, regardless of scaling up to 100+ replicas.
* **Intra-Refresh Request Jitter / Jiffies:**
  The 4 SOAP calls would be executed sequentially with an explicit delay (e.g., 2–3 seconds backoff between requests) rather than in parallel, ensuring we never hit transient rate-limit throttles during the fetch phase.
* **Resilience4j Rate Limiter Tuning:**
  We would adjust the client-side rate limiter to permit a maximum of 8 requests per minute with a 5-second queue time. Any additional retry attempts would block cleanly before hitting the network interface.

---

## 🙋 Question 2: How would you scale the solution to support 20 million requests per day?

### 1. Load Profile Analysis
* **20 Million requests/day** equates to an average of **232 requests per second (RPS)**.
* Assuming standard traffic distributions, we must plan to handle peak loads at **1,200 to 2,500 RPS**.

### 2. Scaling Vectors

#### A. Lock-Free In-Memory Query Path
Because our data store holds reference data in an immutable state swapped via `AtomicReference`, read threads require zero locks, database connections, or IO.
* The query service performs basic filtering, sorting, and pagination on a tiny list (~250 records). A single CPU core can execute this logic in under **100 microseconds**.
* A single container replica running on a standard Kubernetes node (1 CPU, 512Mi memory limit) can easily handle **3,000+ RPS**.
* Scaling to 20M requests/day can be sustained with our minimum **3-replica setup** for high availability, utilizing the Horizontal Pod Autoscaler (HPA) to scale up to 10 replicas only under extreme burst conditions.

#### B. High-Efficiency HTTP / Edge Caching
Reference data is slow-moving and read-heavy. We can offload almost all traffic before it reaches the container heap:
* **Gateway & CDN Caching:**
  Expose the endpoints behind an API Gateway (e.g., Kong, Apigee) or a Content Delivery Network (e.g., Cloudflare). By emitting HTTP response headers (`Cache-Control: public, max-age=3600`), edge caches can serve responses directly.
* **ETag Conditional Validation:**
  Our weak ETag implementation is derived from the SHA-256 data hash and staleness state. Clients sending `If-None-Match: <etag>` will hit the Gateway or local server and receive an immediate `304 Not Modified` response. This bypasses JSON serialization entirely, saving substantial CPU and network bandwidth.

#### C. Database-Free Scaling
Unlike traditional architectures, RDAS does not rely on a relational database (PostgreSQL/MySQL), which removes connection pooling bottlenecks and row-locking issues. The service scales **linearly and statelessy**.

---

## 🙋 Question 3: What additional improvements would you make if given another week to enhance the platform?

If allocated another week, we would focus on these enterprise-grade refinements:

### 1. Shared Snapshot Storage (Decoupling StatefulSets)
Currently, we store the local snapshot cache file on Persistent Volumes bound to a `StatefulSet`. We would migrate this to a shared object store (such as AWS S3 or a shared Redis cluster):
* **Benefit:** Decouples pods from storage volumes, enabling us to run RDAS as a standard, fully stateless Kubernetes `Deployment`. This simplifies cluster scheduling, makes container startup faster, and reduces cloud storage costs.

### 2. Event-Driven Cache Drift Audits (Kafka/RabbitMQ)
While we currently log snapshot diffs structured in `AUDIT` lines locally, we would integrate a message broker to publish these diffs as event feeds:
* **Benefit:** When a country flag, capital city, or phone code changes, RDAS publishes a `ReferenceDataChangedEvent` to a Kafka topic. Downstream systems (e.g., checkout portals, profile databases) can listen to this topic to invalidate their own local cache instantly, replacing polling behaviors with reactive updates.

### 3. Secure Admin Controls & Webhooks
Add a secured administrative path `/api/v1/admin/cache/refresh`:
* **Benefit:** Allows operations teams to force an out-of-band cache refresh if they know the upstream SOAP service has updated their data manually, without waiting for the 12-hour cron. This route would be secured via OAuth2 scopes or client certificate checks.

### 4. Pact / Consumer-Driven Contract Testing
Integrate Contract Testing (using Pact or Spring Cloud Contract) in the CI/CD pipeline:
* **Benefit:** Automatically verifies that changes to the SOAP WSDL schema or unexpected field truncations are caught immediately at the build phase, preventing deployments from breaking the anti-corruption layer.

### 5. Grafana Dashboard & Unified Alert Rules
Package a standardized Prometheus monitoring dashboard and alert manager configuration:
* **Benefit:** Provides real-time visibility into SOAP latency, circuit breaker states, Caffeine cache hit ratios, and alert triggers if cache age exceeds 48 hours.
