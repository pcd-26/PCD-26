# JPF Verification Area

This directory is included in the Assignment 1 deliverable for inspection.
Running JPF is optional and is not required to run the application.

This directory hosts the Java PathFinder verification artifacts for
Assignment 1.

The purpose of this area is to keep the verification models separate from the
Maven build and from the Java 17 production sources. The harnesses are meant
to stay small, Java 11-friendly, and focused on synchronization behavior.

## Delivery contents

- `src/pcd/poool/verification/jpf/ThreadedMiniHarness.java`
- `src/pcd/poool/verification/jpf/TaskBasedMiniHarness.java`
- `src/pcd/poool/model/physics/threaded/PhysicsWorkersJpfHarness.java`
- `src/pcd/poool/verification/jpf/TaskBasedPhysicsBatchHarness.java`
- `threaded-minimal.jpf`
- `taskbased-minimal.jpf`
- `threaded-physics-workers.jpf`
- `taskbased-physics-batch.jpf`

The repository also contains optional bootstrap and launcher scripts, but the
delivery archive intentionally omits them together with generated outputs and
the local JPF runtime. The submitted artifacts are therefore inspection-only.

## Scope

The harnesses model only the concurrent protocol:

- two independently scheduled command submissions and exact-once execution;
- controller-owned board access during a tick;
- two-worker work dispatch, completion, and barrier synchronization;
- snapshot publication after commit;
- bounded termination of the scenario.

`PhysicsWorkersJpfHarness` is the direct exception to the reduced-model
approach: it instantiates the production `PhysicsWorker` and
`WorkerCompletionMonitor` classes. It exhaustively checks two worker chunks
over two consecutive phases, the completion barrier before each commit, worker
reuse, and worker shutdown. The chunk body is intentionally small, so JPF
explores synchronization rather than numerical physics.

`TaskBasedPhysicsBatchHarness` is a validation-only model of the task-based
physics phase. It recreates the fixed-pool submit-all, wait-all protocol with
two chunks across two phases, and checks commit-after-completion, pool reuse,
and shutdown without changing production code.

They do not model:

- full physics;
- GUI rendering;
- bot strategy;
- large workloads;
- production `ExecutorService` behavior.

## Run The Models

The helper script can run either:

- locally, using the runtime jars under `assignment-1/verification/jpf/.jpf-core/build`;
- inside Docker, using `--docker`.

The local JPF runtime requires Java 11. With a newer JVM, the helper
automatically falls back to Docker so the default command still works.
Regardless of the execution mode, a run succeeds only when JPF prints
`no errors detected`; the process exit code alone is not sufficient.

## Execution Notes

The recommended execution flow is documented in
[`docs/verification/jpf-docker-workflow.md`](../../docs/verification/jpf-docker-workflow.md).
In short, `run_jpf.py` compiles the Java 11 harnesses into
`assignment-1/target/jpf-classes` before running the selected models.

To start JPF with a local checkout, run:

```bash
python assignment-1/verification/jpf/bootstrap_jpf.py
```

Then either run Docker mode:

PowerShell:

```powershell
python assignment-1/verification/jpf/run_jpf.py --docker --model all
```

POSIX shell:

```bash
python assignment-1/verification/jpf/run_jpf.py --docker --model all
```

Docker mode normalizes the Gradle wrapper line endings inside the container
before building the JPF runtime jars, so it works even when the checkout was
cloned on Windows. It uses the focused `buildJars` task rather than running the
complete `jpf-core` test suite for every model-checking session.

Or, if you already built JPF locally, run without Docker:

PowerShell:

```powershell
python assignment-1/verification/jpf/run_jpf.py --model all
```

POSIX shell:

```bash
python assignment-1/verification/jpf/run_jpf.py --model all
```

### Standard JPF Runtime

When `jpf-core` is built from the local checkout, the standard runtime files
are the jars under:

`assignment-1/verification/jpf/.jpf-core/build`

The runner uses:

- `build/RunJPF.jar`
- `build/jpf.jar`
- `build/jpf-classes.jar`
- `build/jpf-annotations.jar`
- `build/asm-9.5.jar`
- `build/junit-4.13.1.jar`

The model-level explanation is documented in
[`docs/verification/jpf-models.md`](../../docs/verification/jpf-models.md).
