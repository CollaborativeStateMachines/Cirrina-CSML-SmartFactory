# Cirrina CSML Smart Factory

A Smart Factory application implemented with **Collaborative State Machines (CSMs)** and **CSML/Pkl**, together with the supporting components used to execute and benchmark the application on the Cirrina runtime.

The repository models a distributed part-assembly workflow in which several state-machine instances coordinate through events and invoke external services for simulated physical operations. It also includes benchmark utilities for workload generation and experiment-level metric collection.

## Overview

The Smart Factory application is composed of the following logical components:

| Component | Responsibility |
| --- | --- |
| Job Controller | Coordinates the overall production workflow and tracks completed products. |
| Conveyor Belt | Models transport of parts through the factory. |
| Robotic Arm | Performs pickup, assembly, and reset operations. |
| Assembly Controller | Coordinates part detection, image capture/scanning, and assembly-related actions. |
| Message Processor | Handles application messages such as email and SMS notifications. |
| Monitor | Collects application-level statistics. |

The benchmark support layer contains three standalone Kotlin/JVM applications:

- **Smart Factory Service** — provides the HTTP endpoints invoked by the state machines and emits asynchronous peripheral events through Zenoh.
- **Metrics Collector** — observes benchmark lifecycle events and records production-time and observed-arrival-rate data.
- **Event Publisher** — generates incoming part-arrival events according to a configurable workload.

All three support applications are independently buildable Gradle projects and are containerized with Docker.

## Repository Structure

```text
.
├── smartFactory/
│   └── ...                         # CSML/Pkl Smart Factory application
└── benchmark-s1/
    ├── smart-factory-service/      # External Smart Factory services
    ├── metrics-collector/          # Experiment-level metric collection
    └── event-publisher/            # Configurable workload generator
```

Each benchmark component contains its own Gradle wrapper, build configuration, source tree, and Dockerfile.

## Technology Stack

- Kotlin / JVM 25
- Gradle
- Cirrina
- CSML / Pkl
- Eclipse Zenoh
- Docker

## Prerequisites

For containerized builds:

- Docker
- Network access to pull `collaborativestatemachines/cirrina:unstable` and Maven/Gradle dependencies

For direct Gradle development:

- JDK 25
- The Gradle wrapper included with each component

> [!NOTE]
> The benchmark applications compile against Cirrina libraries located at `/opt/cirrina/lib`. The Docker build supplies these libraries from the official Cirrina image. A direct host-side Gradle build therefore requires the same libraries to be available at that path.

## Building the Benchmark Components

The Dockerfiles use multi-stage builds. Application dependencies are packaged into Shadow JARs, while Cirrina runtime libraries are supplied by the Cirrina image and added to the runtime classpath.

### Smart Factory Service

```bash
cd benchmark-s1/smart-factory-service
docker build -t smartfactoryservice:csm .
```

### Metrics Collector

```bash
cd benchmark-s1/metrics-collector
docker build -t smartfactorycollector:csm .
```

### Event Publisher

```bash
cd benchmark-s1/event-publisher
docker build -t smartfactoryep:csm .
```

## Runtime Configuration

### Smart Factory Service

The service listens on port `6000` and exposes only the endpoints required for the selected role.

| Variable | Required | Description |
| --- | --- | --- |
| `ZENOH_CONFIG_URI` | No | Path to a Zenoh configuration file. Defaults to the Zenoh library configuration when omitted. |
| `SERVICE_ROLE` | Yes | Selects the endpoints provided by the process. Supported values are `monitor`, `mp`, `belt`, `arm`, and `ac`. |

Example:

```bash
docker run --rm \
  --network host \
  -v "$PWD/zenoh.json5:/service/zenoh.json5:ro" \
  -e ZENOH_CONFIG_URI=/service/zenoh.json5 \
  -e SERVICE_ROLE=arm \
  smartfactoryservice:csm
```

### Metrics Collector

The collector subscribes to benchmark lifecycle events and writes its output under `/collector/metrics`.

| Variable | Required | Description |
| --- | --- | --- |
| `ZENOH_CONFIG_URI` | No | Path to a Zenoh configuration file. |
| `RUN_ID` | No | Identifier included in the production-time output filename. Defaults to `0`. |

The collector produces:

```text
metrics/
├── ProductionTimes_run_<RUN_ID>.csv
└── arrival.csv
```

Example:

```bash
mkdir -p metrics

docker run --rm \
  --network host \
  -v "$PWD/zenoh.json5:/collector/zenoh.json5:ro" \
  -v "$PWD/metrics:/collector/metrics:rw" \
  -e ZENOH_CONFIG_URI=/collector/zenoh.json5 \
  -e RUN_ID=1 \
  smartfactorycollector:csm
```

### Event Publisher

The event publisher generates `eBeamInterruptedStart` events and stops when the production workflow emits `eJobDone`.

| Variable | Required | Default | Description |
| --- | --- | ---: | --- |
| `ZENOH_CONFIG_URI` | No | Zenoh default | Path to a Zenoh configuration file. |
| `PART_ARRIVAl_RATE_PER_SEC` | No | `1.0` | Configured part-arrival rate in events per second. |
| `PUBLISH_START_DELAY` | No | `0` | Delay before workload generation starts, in milliseconds. |
| `PUBLISH_MODE` | No | `0` | Arrival-generation mode. `1` uses a fixed inter-arrival interval; the default uses exponentially distributed inter-arrival times. |

Example:

```bash
docker run --rm \
  --network host \
  -v "$PWD/zenoh.json5:/publisher/zenoh.json5:ro" \
  -e ZENOH_CONFIG_URI=/publisher/zenoh.json5 \
  -e PART_ARRIVAl_RATE_PER_SEC=100 \
  -e PUBLISH_START_DELAY=120000 \
  smartfactoryep:csm
```

## Running the Complete Application

The three benchmark-support containers are not standalone applications. A complete run additionally requires:

1. a reachable Zenoh deployment,
2. a Cirrina runtime and associated dependencies,

At runtime, the Smart Factory instances correspond to:

```text
messageProcessor
jobController
conveyorBelt
arm
monitor
assemblyController
```

The exact deployment topology can be adapted to the target environment. For distributed benchmark runs, ensure that all nodes use synchronized system clocks before collecting latency measurements.

## Benchmark Flow

At a high level, an experiment proceeds as follows:

1. Start the infrastructure and Cirrina state-machine instances.
2. Start the role-specific Smart Factory service processes.
3. Start the metrics collector.
4. Start the event publisher with the desired arrival rate.
5. The publisher emits incoming part events.
6. State machines coordinate the production workflow and invoke external services.
7. The collector records the production interval between `eProductionStarted` and `eJobDone`.
8. The publisher stops after observing `eJobDone`.
9. Metrics are flushed and collected for analysis.

The benchmark separates the configurable workload generator from the application itself so that different arrival rates can be evaluated without modifying the CSML/Pkl application.

## Development

### Formatting

The Kotlin benchmark projects use `ktfmt` with Google style.

From any benchmark component:

```bash
./gradlew ktfmtFormat
```

To check formatting without modifying files:

```bash
./gradlew ktfmtCheck
```

### Building the Shadow JAR

Inside a benchmark component:

```bash
./gradlew shadowJar
```

For the current setup, Docker is the recommended build path because the build expects Cirrina runtime libraries under `/opt/cirrina/lib`.

## Acknowledgements

This repository builds on the **Cirrina** runtime and the **Collaborative State Machine (CSM)** programming model developed at the University of Innsbruck.
