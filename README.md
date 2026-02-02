# Distributed Locking & Leader Election Service

A distributed coordination service inspired by Apache ZooKeeper, built using Spring Boot.  
This service provides Leader Election, Distributed Locks, Heartbeats, and Failure Detection to help multiple services safely coordinate in a distributed system.

The project focuses on solving real-world distributed systems problems such as race conditions, split-brain scenarios, timeouts, and basic consensus mechanisms.

---

## Problem Statement

In distributed systems and microservices architectures, multiple instances run concurrently. Common challenges include:
- Ensuring only one node acts as a leader
- Coordinating access to shared resources
- Detecting node failures reliably
- Recovering automatically from crashes

This service acts as a centralized coordination layer to solve these problems.

---

## Core Features

### Leader Election
Multiple nodes participate in leader election. At any point in time, exactly one node is elected as the leader. If the current leader crashes or becomes unreachable, a new leader is elected automatically.

### Distributed Locks
Provides mutual exclusion across distributed nodes. Only one node can acquire a lock at a time. Locks are fair (FIFO-based) and automatically released if the owning node crashes.

### Heartbeats
Each node periodically sends heartbeats to indicate it is alive. Heartbeats are used to track node liveness and support failure detection.

### Failure Detection
Nodes that stop sending heartbeats beyond a configurable timeout are considered dead. The system automatically:
- Removes them from leader election
- Releases any locks held by them
- Triggers re-election if required

---

## High-Level Architecture

Nodes communicate with a central Coordination Server using REST APIs.  
The server maintains metadata about nodes, locks, and leadership.

Node responsibilities:
- Register themselves
- Send periodic heartbeats
- Request and release locks

Coordinator responsibilities:
- Track active nodes
- Elect leader
- Manage distributed locks
- Detect failures

---

## Technology Stack

- Java 17
- Spring Boot 3.x
- Spring Web
- Spring Scheduler
- Java Concurrent Collections
- In-memory metadata store (pluggable with Redis / DB)

---

## Core Concepts Explained

### Leader Election Mechanism

- Each node registers with the coordinator
- Nodes are assigned a sequence number
- Node with the smallest sequence number becomes the leader
- Leadership is ephemeral and tied to node liveness
- On leader failure, the next eligible node becomes leader

This prevents split-brain and ensures automatic failover.

---

### Distributed Locking Mechanism

- Each lock maintains a queue of lock requests
- Lock request contains nodeId and sequence number
- The node with the smallest sequence number holds the lock
- Other nodes wait until the lock is released
- If a node holding a lock crashes, the lock is released automatically

This ensures mutual exclusion and fairness.

---

### Heartbeats

- Nodes send heartbeats at fixed intervals
- The coordinator records the last heartbeat timestamp
- Heartbeats act as a lease for leadership and locks

---

### Failure Detection

- A scheduler periodically checks heartbeat timestamps
- If the last heartbeat exceeds the timeout threshold, the node is marked dead
- Cleanup actions are performed automatically

---

## Hard Problems Addressed

### Race Conditions
Handled using synchronized blocks, atomic operations, and concurrent data structures to ensure thread safety.

### Split Brain
Prevented by having a single source of truth and lease-based leadership dependent on heartbeats.

### Timeouts
Configurable timeout values are used to balance sensitivity and stability. Safe retries are supported.

### Consensus Basics
Leader-based coordination ensures consistent state changes. While not a full Raft implementation, it demonstrates core consensus ideas.

---

## Project Structure

src/main/java  
└── com.example.zookeeperlite  
  ├── controller  
  │  ├── NodeController.java  
  │  ├── LeaderController.java  
  │  └── LockController.java  
  ├── service  
  │  ├── NodeRegistryService.java  
  │  ├── LeaderElectionService.java  
  │  ├── LockService.java  
  │  ├── HeartbeatService.java  
  │  └── FailureDetectionService.java  
  ├── model  
  │  ├── NodeInfo.java  
  │  ├── LockEntry.java  
  │  └── LeaderInfo.java  
  └── scheduler  
    └── FailureMonitorScheduler.java  

---

## REST API Endpoints

### Register Node
Registers a node with the coordination service.

POST /nodes/register

Request:
{
  "nodeId": "node-1",
  "host": "10.0.0.1"
}

Response:
{
  "status": "REGISTERED"
}

---

### Send Heartbeat
Used by nodes to indicate they are alive.

POST /nodes/heartbeat

Request:
{
  "nodeId": "node-1"
}

Response:
{
  "status": "ALIVE"
}

---

### Get Current Leader
Returns the currently elected leader.

GET /leader

Response:
{
  "leaderId": "node-1"
}

---

### Acquire Distributed Lock
Attempts to acquire a lock.

POST /locks/acquire/{lockName}

Request:
{
  "nodeId": "node-2"
}

Response (if acquired):
{
  "status": "LOCK_ACQUIRED"
}

Response (if waiting):
{
  "status": "WAITING"
}

---

### Release Distributed Lock
Releases a lock held by a node.

POST /locks/release/{lockName}

Request:
{
  "nodeId": "node-2"
}

Response:
{
  "status": "LOCK_RELEASED"
}

---

### List Locks (Debug)
Returns current lock state.

GET /locks

---

## Failure Scenarios Handled

- Leader node crash
- Node crash while holding a lock
- Missed heartbeats
- Concurrent lock requests
- Node restarts

---

## Scalability and Design Notes

- Stateless REST APIs
- Metadata store is pluggable
- Supports horizontal scaling
- Designed for high concurrency

---

## Future Enhancements

- Raft-based consensus algorithm
- Watchers and event notifications
- Persistent state snapshots
- Redis / database-backed metadata
- Multi-region leader election

---

## Interview Relevance

This project demonstrates hands-on understanding of:
- Distributed locking
- Leader election
- Failure detection
- Coordination services
- ZooKeeper internals

It is suitable for SDE-2 and senior backend interviews.

---

## Conclusion

This project is a simplified but practical implementation of a ZooKeeper-like coordination service.  
It focuses on correctness, fault tolerance, and real distributed systems challenges rather than simple CRUD operations.
