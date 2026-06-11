Question 1: How would your solution change if the SOAP provider reduced the limit to 10 requests per minute?

1. Analysis of Current Load

Under my current decoupled snapshot architecture, the SOAP provider is never on the hot path of client requests. All client queries are served directly from an in-memory snapshot maintained by each replica.

* A full snapshot refresh requires exactly 4 SOAP requests (one for the consolidated country/language list and three enrichment requests for continent, currency, and language metadata).
* With a twice-daily refresh schedule, each pod performs 8 SOAP requests per day.
* A limit of 10 requests per minute translates to 14,400 requests per day.

Even with 3 replicas, the cluster would generate only 24 requests per day, which is a very small fraction of the provider’s allowance. As a result, the overall architecture would not require a fundamental redesign.

2. Additional Safeguards

To ensure the solution remains robust during deployments, restarts, and scaling events, I would introduce the following enhancements:

Distributed Refresh Coordination

I would use a distributed locking mechanism such as ShedLock or Kubernetes Leases to ensure that only one pod performs scheduled refreshes. The refreshed snapshot would then be written to a shared storage layer (for example, AWS S3, Redis, or a ReadWriteMany volume), allowing all replicas to reload it.

This guarantees that SOAP traffic remains constant regardless of the number of application replicas.

Sequential Request Execution

Instead of issuing the four SOAP calls in parallel, I would execute them sequentially with a small delay between requests. This minimises the risk of accidental throttling and provides a more predictable request pattern.

Rate Limiter Tuning

I would configure Resilience4j Rate Limiter to stay comfortably below the provider’s threshold, allowing a safety margin for retries and operational activities while preventing excessive outbound traffic.

---

Question 2: How would you scale the solution to support 20 million requests per day?

1. Load Profile Analysis

* 20 million requests per day equate to approximately 232 requests per second (RPS) on average.
* To accommodate traffic spikes and uneven usage patterns, I would design for peak loads of 1,000–2,500 RPS.

2. Scaling Strategy

A. Lock-Free In-Memory Query Path

The core advantage of my design is that all reference data is held in immutable in-memory snapshots and accessed through an AtomicReference.

This means request processing requires:

* No database calls
* No SOAP calls
* No distributed cache lookups
* No synchronisation locks on reads

Each request performs filtering, sorting, and pagination against a relatively small dataset, making response generation extremely lightweight.

Because the application is largely CPU-bound and stateless, horizontal scaling becomes straightforward using Kubernetes HPA. A small number of replicas would comfortably support the target traffic while preserving high availability.

B. Edge and Gateway Caching

Since reference data changes infrequently, I would aggressively leverage HTTP caching.

API Gateway / CDN Caching

I would place the service behind an API Gateway or CDN and emit appropriate cache headers such as:

Cache-Control: public, max-age=3600

This allows many requests to be served before they even reach the application.

ETag-Based Validation

I would generate ETags from the snapshot hash and staleness state. Clients sending If-None-Match headers would receive lightweight 304 Not Modified responses whenever the data has not changed.

This significantly reduces serialisation overhead, bandwidth consumption, and overall infrastructure costs.

C. Kubernetes Auto-Scaling

I would configure:

* Horizontal Pod Autoscaling based on CPU and request metrics
* Multiple replicas distributed across availability zones
* Rolling deployments with zero downtime
* Pod disruption budgets to maintain availability during upgrades

This ensures the platform can absorb sudden increases in traffic while maintaining service reliability.

---

Question 3: What additional improvements would you make if given another week to enhance the platform?

If given an additional week, I would focus on strengthening operational maturity, observability, and platform scalability.

1. Shared Snapshot Storage

Currently, snapshots are persisted on pod-attached storage. I would move snapshot persistence to a shared storage layer such as AWS S3 or Redis.

Benefits:

* Fully stateless application pods
* Faster pod startup and recovery
* Simpler Kubernetes deployments
* Reduced operational complexity

2. Event-Driven Change Notifications

I would publish snapshot differences to Kafka or RabbitMQ whenever reference data changes.

For example, if a country code, currency, or language entry changes, the platform would emit a ReferenceDataChangedEvent.

Benefits:

* Downstream systems receive updates immediately.
* Eliminates polling requirements
* Improves overall ecosystem consistency

3. Secure Administrative Refresh Controls

I would introduce a secured endpoint such as:

POST /api/v1/admin/cache/refresh

This would allow authorised operations teams to trigger an immediate refresh when upstream data changes unexpectedly.

Security could be enforced using OAuth2 scopes, mutual TLS, or API gateway policies.

4. Contract Testing

I would add consumer-driven contract testing using Pact or Spring Cloud Contract.

This would automatically detect:

* WSDL schema changes
* Field type modifications
* Unexpected response structure changes

before they reach production.

5. Enhanced Observability

I would package the solution with a production-ready monitoring stack, including:

* Prometheus metrics
* Grafana dashboards
* AlertManager rules

Key indicators would include:

* SOAP response latency
* Circuit breaker state
* Snapshot age
* Refresh success rate
* Cache hit ratio
* Application error rates