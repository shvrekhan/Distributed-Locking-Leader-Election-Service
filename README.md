# Distributed Locking & Leader Election Service

A ZooKeeper-inspired distributed coordination service built with Spring Boot.  
Implements session-based leader election, FIFO distributed locks with fencing tokens, 3-state failure detection, and real-time watch notifications over SSE.

---

## Problem Statement

In distributed systems, multiple service instances run concurrently. Core challenges:
- Ensuring exactly one node acts as leader at any time
- Coordinating exclusive access to shared resources safely
- Detecting node failures reliably without false positives
- Automatically recovering from crashes without manual intervention

This service acts as a centralized coordination layer to solve these problems.

---

## Core Features

### Session Model
Nodes register as **sessions** (not just nodes). A session carries a TTL, sequence number, and lifecycle state. All resources — leadership, locks — are tied to a session. When a session dies, all its resources are automatically released.

### Epoch-Based Leader Election
Leader election uses a monotonically increasing **epoch number** (inspired by Raft terms). Every new election increments the epoch atomically. Any operation carrying a stale epoch is rejected — this prevents split-brain scenarios.

- Smallest sequence number among ALIVE sessions wins
- Epoch increments on every election, never resets
- Re-election triggers automatically when leader session dies

### Distributed Locks with Fencing Tokens
FIFO-based distributed locks with **fencing tokens** — the key safety property missing from toy implementations.

- Each lock acquisition returns a monotonically increasing fencing token
- Downstream resources validate the token before accepting writes
- A GC-paused node that wakes up with a stale token gets rejected
- Locks are automatically released when the holding session dies

### 3-State Failure Detection
Sessions transition through `ALIVE → SUSPECT → DEAD` states.

- `SUSPECT` state gives dependent services time for graceful handoff before full death
- TTL expiry uses **±20% jitter** to prevent thundering herd on the coordinator
- Internal `ApplicationEventPublisher` decouples failure detection from election and lock cleanup

### Watch API (Server-Sent Events)
Real-time push notifications for leadership changes and lock events — no polling required.

---

## Architecture

```
Client Nodes                 Coordination Server
┌─────────┐                 ┌──────────────────────────────────┐
│ Node A  │──┐              │  REST API Gateway                │
├─────────┤  │──────────────│  (validation · idempotency)      │
│ Node B  │──┤              │                                  │
├─────────┤  │              │  ┌─────────────┐ ┌───────────┐  │
│ Node C  │──┘              │  │ Session Mgr │ │ Election  │  │
└─────────┘                 │  │ TTL · jitter│ │ Engine    │  │
                            │  └─────────────┘ └───────────┘  │
                            │  ┌─────────────┐ ┌───────────┐  │
                            │  │ Lock Manager│ │ Failure   │  │
                            │  │ FIFO·fencing│ │ Detector  │  │
                            │  └─────────────┘ └───────────┘  │
                            │                                  │
                            │  State Store (in-memory/Redis)   │
                            └──────────────────────────────────┘
```

---

## Technology Stack

- Java 17
- Spring Boot 3.x
- Spring Web, Spring Scheduler
- `ConcurrentHashMap`, `AtomicLong`, `LinkedBlockingDeque`
- Micrometer (Prometheus metrics)
- In-memory state store (pluggable via `StateStore` interface)

---

## Key Design Decisions

### Sessions, not Nodes
Sessions are ephemeral by design — they carry TTL and expire. This makes the failure contract explicit: no heartbeat = session dies = all tied resources release automatically.

### Fencing Tokens
Each lock acquisition returns a monotonically increasing integer. Downstream resources must check: `token >= lastAcceptedToken`. If not, the write is rejected. This prevents a GC-paused node from corrupting shared state after its lock expired.

```java
// On acquire — always increasing
return fencingCounter.getAndIncrement();

// On release — reject stale holders
if (token < fencingCounter.get() - 1) throw new StaleFencingTokenException();
```

### Epoch Numbers
Every election increments the epoch atomically. Stale epoch = stale leader. Any service receiving a response with epoch < current knows it must re-read state.

```java
public LeaderInfo elect(String winnerId) {
    long newEpoch = epoch.incrementAndGet(); // never resets
    this.leaderId = winnerId;
    return new LeaderInfo(winnerId, newEpoch, Instant.now());
}
```

### Jitter in TTL
Prevents thundering herd — 100 nodes with identical TTLs would all expire and renew simultaneously, spiking the coordinator.

```java
double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
Instant expiresAt = Instant.now().plusMillis((long)(ttlMs * jitter));
```

### Internal Event Bus
`SessionRegistry` publishes `SessionStateChangedEvent`. `ElectionService` and `LockService` each listen independently via `@EventListener`. No direct service-to-service calls — clean decoupling.

---

## Project Structure

```
src/main/java
└── com.example.zookeeperlite
    ├── controller
    │   ├── SessionController.java
    │   ├── LeadershipController.java
    │   ├── LockController.java
    │   └── WatchController.java
    ├── service
    │   ├── SessionRegistry.java
    │   ├── LeaderElectionService.java
    │   ├── LockService.java
    │   ├── FailureDetectionService.java
    │   └── SseEmitterRegistry.java
    ├── model
    │   ├── SessionInfo.java
    │   ├── LeadershipState.java
    │   ├── LockState.java
    │   └── SessionStateChangedEvent.java
    ├── store
    │   ├── StateStore.java          (interface)
    │   └── InMemoryStateStore.java
    └── scheduler
        └── FailureMonitorScheduler.java
```

---

## REST API

### Sessions

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/v1/sessions` | Register session, returns sequenceNumber + epoch |
| `PUT` | `/v1/sessions/{id}/renew` | Renew TTL lease (idempotent) |
| `DELETE` | `/v1/sessions/{id}` | Graceful disconnect, releases all resources |

### Leadership

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/v1/leadership` | Current leader + epoch (503 during election) |
| `POST` | `/v1/leadership/campaigns` | Start candidacy, returns campaignId |
| `GET` | `/v1/leadership/campaigns/{id}` | Poll campaign outcome (ELECTED / LOST) |

### Locks

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/v1/locks/{namespace}/{name}` | Acquire lock — returns fencing token or queue position |
| `DELETE` | `/v1/locks/{namespace}/{name}` | Release lock — requires fencing token (412 if stale) |
| `GET` | `/v1/locks/{namespace}` | List locks in namespace |
| `GET` | `/v1/locks/{namespace}/{name}/queue` | Inspect wait queue |

### Watch (SSE)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/v1/watch/leadership` | Stream leader change events |
| `GET` | `/v1/watch/locks/{namespace}` | Stream lock acquired/released/expired events |

### Observability

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/v1/health` | Component-level health (sessions, election, locks, store) |
| `GET` | `/v1/metrics` | Prometheus scrape endpoint |
| `GET` | `/v1/admin/state` | Full state dump (debug only) |

---

## Failure Scenarios Handled

| Scenario | Mechanism |
|----------|-----------|
| Leader crash | Session dies → epoch increments → next lowest sequence elected |
| Lock holder crash | Session dies → lock released → next waiter promoted with token+1 |
| GC pause (stale lock) | Fencing token rejected by downstream resource |
| Thundering herd | TTL jitter spreads renewal load |
| Concurrent lock requests | `computeIfAbsent()` + `synchronized` on LockState |
| Split brain | Epoch validation rejects stale leaders |

---

## Observability

Micrometer metrics exposed at `/v1/metrics`:
- `lock_acquisitions_total` — by namespace and result
- `lock_wait_duration_ms` — histogram (p50/p99)
- `session_deaths_total` — by cause (ttl_expired / graceful)
- `election_count_total`

Structured JSON logging with `sessionId` and `lockName` in MDC for every request.

---

## Configuration

```yaml
coordinator:
  session:
    default-ttl-ms: 5000
  lock:
    max-queue-size: 100
  failure-detection:
    interval-ms: 200
```

---

## Running Locally

```bash
# Start coordinator
mvn spring-boot:run

# Docker (coordinator + 3 example nodes)
docker-compose up
```

---

## Hard Problems Addressed

- **Race conditions** — `ConcurrentHashMap.computeIfAbsent()` for atomic state creation; `synchronized` blocks for compound operations
- **Split brain** — Epoch-based leadership; stale epoch rejected on every operation
- **GC pause / stale locks** — Fencing tokens validated by downstream resources
- **Thundering herd** — ±20% TTL jitter on session creation
- **Tight coupling** — Internal event bus via `ApplicationEventPublisher`

---

## Future Enhancements

- Raft-based consensus for multi-coordinator deployments
- Redis-backed `StateStore` implementation
- Event replay on SSE reconnect (Last-Event-ID)
- Shared (read) lock mode alongside exclusive locks
- Multi-region leader election

---

## References

- *Designing Data-Intensive Applications* — Martin Kleppmann (Ch. 8 — fencing tokens)
- Apache ZooKeeper documentation — ephemeral znodes, sequential nodes
- Raft paper — epoch/term concept
