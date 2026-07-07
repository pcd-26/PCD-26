# JPF Verification Area

This directory hosts the Java PathFinder verification artifacts for
Assignment 1.

The purpose of this area is to keep the verification models separate from the
Maven build and from the Java 17 production sources. The harnesses are meant
to stay small, Java 11-friendly, and focused on synchronization behavior.

## Contents

- `src/pcd/poool/verification/jpf/ThreadedMiniHarness.java`
- `src/pcd/poool/verification/jpf/TaskBasedMiniHarness.java`
- `threaded-minimal.jpf`
- `taskbased-minimal.jpf`
- `target/jpf-classes` during test execution
- `bootstrap_jpf.py` to clone `jpf-core` locally
- `run_jpf.py` to launch a model locally or through Docker
- `.jpf-core/` as a local, gitignored checkout of `jpf-core`

## Scope

The harnesses model only the concurrent protocol:

- command submission and draining;
- exclusive board ownership during a tick;
- work dispatch and completion;
- snapshot publication after commit;
- bounded termination of the scenario.

They do not model:

- full physics;
- GUI rendering;
- bot strategy;
- large workloads;
- production `ExecutorService` behavior.

## Next Step

The next step is to run the JPF integration test. The helper script can run
either:

- locally, using the runtime jars under `assignment-1/verification/jpf/.jpf-core/build`;
- inside Docker, using `--docker`.

If the local Java runtime is too new for the JPF build, the helper
automatically falls back to Docker so the default command still works.

## Execution Notes

The recommended execution flow is documented in
[`docs/verification/jpf-docker-workflow.md`](../../docs/verification/jpf-docker-workflow.md).
In short, the JUnit test compiles the harnesses into
`assignment-1/target/jpf-classes` and then runs both `.jpf` files through the
selected JPF launcher path or the Docker flow.

To start JPF with a local checkout, run:

```bash
python assignment-1/verification/jpf/bootstrap_jpf.py
```

Then either run Docker mode:

PowerShell:

```powershell
python assignment-1/verification/jpf/run_jpf.py --docker --model both
```

POSIX shell:

```bash
python assignment-1/verification/jpf/run_jpf.py --docker --model both
```

Docker mode normalizes the Gradle wrapper line endings inside the container
before building `jpf-core`, so it works even when the checkout was cloned on
Windows.

Or, if you already built JPF locally, run without Docker:

PowerShell:

```powershell
python assignment-1/verification/jpf/run_jpf.py --model both
```

POSIX shell:

```bash
python assignment-1/verification/jpf/run_jpf.py --model both
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
