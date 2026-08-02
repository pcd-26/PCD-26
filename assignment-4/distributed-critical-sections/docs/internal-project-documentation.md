# Distributed Critical Sections Internal Project Notebook

This document is the internal map for the RabbitMQ-based distributed critical
section middleware.

It collects the facts that are useful while we simplify code, keep the token
protocol stable, and prepare the report source.

It is not a public README. It is the working note we should consult before
changing code or adapting the delivery material.

## 1. How to use this notebook

When starting a task:

1. read the relevant section below;
2. jump to the linked source or doc file;
3. check the matching tests;
4. update this notebook if the architecture or report story changes.

## 2. Assignment brief, in our own words

This optional sub-project implements a simple high-level middleware that
supports critical sections for distributed processes.

The important points are:

- the process using the middleware should not need to know the other processes;
- the implementation must use a message-oriented middleware;
- RabbitMQ is used as the broker and token store;
- the protocol should tolerate crashes by relying on broker semantics.

## 3. What the brief asks for, and where it is covered

| Requirement from the brief | Where it is covered | Status |
| --- | --- | --- |
| process-level critical sections | `pcd.dcs.DistributedCriticalSection` | implemented |
| message-oriented middleware | RabbitMQ queues and acknowledgments | implemented |
| broker-backed mutual exclusion | token queue design | implemented |
| crash tolerance and recovery | requeue behavior and bootstrap logic | implemented |
| no need to know other processes | public API hides peer coordination | implemented |

## 4. Source of truth map

### 4.1 Public-facing entry points

- [`README.md`](../README.md)
- [`docker-compose.rabbitmq.yml`](../docker-compose.rabbitmq.yml)
- [`run-dcs.sh`](../run-dcs.sh)
- [`run-dcs.ps1`](../run-dcs.ps1)
- [`test-dcs.sh`](../test-dcs.sh)
- [`test-dcs.ps1`](../test-dcs.ps1)

### 4.2 Code

- `src/main/java/pcd/dcs`
- `src/main/java/pcd/dcs/demo`

### 4.3 Tests

- `src/test/java/pcd/dcs`

### 4.4 Report source

- `report/`

## 5. Architecture story

### 5.1 Token queue

The critical section is represented by a durable RabbitMQ token queue.

What to keep in mind:

- the queue contains exactly one persistent token when the section is idle;
- a process acquires the token with manual acknowledgments;
- only one process can own the token at a time;
- requeueing the token restores availability after exit or crash.

### 5.2 Bootstrap lock

Bootstrap is serialized with a temporary exclusive lock queue.

What to keep in mind:

- it prevents two processes from seeding the token at the same time;
- it keeps initialization recoverable;
- it avoids creating a separate persistent marker that could drift from the token.

### 5.3 Safe release

Release must hand the token back without losing it.

What to keep in mind:

- the consumer is cancelled before requeueing;
- the token is requeued with `basicNack(..., requeue = true)`;
- release should remain idempotent from the caller point of view as much as the API allows.

### 5.4 Demo and scripts

The scripts start RabbitMQ automatically when needed and then launch the demo
or test flow.

What to keep in mind:

- they are convenience wrappers around the same core protocol;
- the project still depends on a reachable broker;
- the local Docker workflow should remain simple to explain.

## 6. Tests that matter most

The tests should protect the behaviors that refactoring can accidentally break:

- concurrent initialization;
- single-token creation;
- bootstrap interruption recovery;
- crash while holding the token;
- mutual exclusion across multiple processes;
- repeated acquire/release cycles;
- release without ownership;
- double release rejection;
- independence of different critical-section names.

## 7. Report-ready concept list

If we want a short conceptual summary for the report, it is this one:

- token-based mutual exclusion;
- RabbitMQ as the broker and recovery mechanism;
- bootstrap serialization through a temporary lock queue;
- manual acknowledgments for ownership;
- requeue-based crash recovery;
- a minimal middleware API that hides other processes.

