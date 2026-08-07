# JPF Docker Workflow

This document describes the intended execution flow for the minimal JPF
verification models in Assignment 1.

The workflow is intentionally split from the Maven build:

- the production project stays on Java 17 and Maven;
- the JPF harnesses stay small and separate;
- Docker provides the JPF runtime environment.

## 1. Prepare JPF

Use the official `jpf-core` repository and its Docker support.

The JPF project README states that the container can be built and started with
the provided `Dockerfile` and `docker-compose.yml`.

If you are using the `jpf-core` Docker setup, start the JPF container from the
`jpf-core` checkout and open a shell in it. A typical pattern is:

```bash
cd <path-to-jpf-core>
docker compose build
docker compose run --rm <jpf-service> bash
```

If you built a standalone image instead, the equivalent pattern is:

```bash
docker build -t jpf-core .
docker run --rm -it -v "${PWD}:/repo" -w /repo jpf-core bash
```

If you prefer to keep the JPF source tree inside this repository, use the local
bootstrap script:

```bash
python assignment-1/verification/jpf/bootstrap_jpf.py
```

That clones `jpf-core` into `assignment-1/verification/jpf/.jpf-core`, which is
ignored by git.

Inside that container, JPF can be built from source with the usual Gradle
build.

The helper script can run JPF in two ways:

- Docker mode, which builds `jpf-core` inside the container and runs JPF there;
- local mode, which uses the runtime classpath produced by the local `jpf-core` build.

If the local Java runtime is too new for the JPF build, the helper falls back
to Docker automatically.

The standard local runtime jars include:

- `assignment-1/verification/jpf/.jpf-core/build/RunJPF.jar`
- `assignment-1/verification/jpf/.jpf-core/build/jpf.jar`
- `assignment-1/verification/jpf/.jpf-core/build/jpf-classes.jar`
- `assignment-1/verification/jpf/.jpf-core/build/jpf-annotations.jar`
- `assignment-1/verification/jpf/.jpf-core/build/asm-9.5.jar`
- `assignment-1/verification/jpf/.jpf-core/build/junit-4.13.1.jar`

## 2. Compile The Minimal Harnesses

The harnesses live under:

- `assignment-1/verification/jpf/src/pcd/poool/verification/jpf`

They are compiled by `run_jpf.py` (and independently by the JUnit integration
test) into:

- `assignment-1/target/jpf-classes`

The important point is that these runtime entries must come from `jpf-core`,
not from the full application build. The launcher performs the harness
compilation automatically, so the documented Docker command also works from a
clean Maven target directory.

## 3. Run The Thread-Based Model

The thread-based config is:

- `assignment-1/verification/jpf/threaded-minimal.jpf`

The run should target:

- `pcd.poool.verification.jpf.ThreadedMiniHarness`

The expected outcome is that JPF explores the small state space and either
confirms the assertions or produces a short counterexample trace.

## 4. Run The Task-Based Model

The task-based config is:

- `assignment-1/verification/jpf/taskbased-minimal.jpf`

The run should target:

- `pcd.poool.verification.jpf.TaskBasedMiniHarness`

As with the threaded model, the goal is to verify the protocol around command
draining, work completion, and snapshot publication.

## 5. Run The JUnit Integration Test

The actual JPF-backed check is performed by the JUnit test:

- `pcd.poool.verification.JpfVerificationArtifactsTest`

The test compiles the harnesses and then runs both JPF configs:

- `threaded-minimal.jpf`
- `taskbased-minimal.jpf`

Recommended Docker invocation:

PowerShell:

```powershell
python assignment-1/verification/jpf/run_jpf.py --docker --model both
```

POSIX shell:

```bash
python assignment-1/verification/jpf/run_jpf.py --docker --model both
```

The Docker helper normalizes the Gradle wrapper line endings inside the
container before building `jpf-core`, which avoids the `^M` wrapper failure on
Windows checkouts.

If you already built JPF locally and want to run the two `.jpf` files manually
from `assignment-1/` instead of through Maven, use:

PowerShell:

```powershell
java -ea -jar verification/jpf/.jpf-core/build/RunJPF.jar verification/jpf/threaded-minimal.jpf
java -ea -jar verification/jpf/.jpf-core/build/RunJPF.jar verification/jpf/taskbased-minimal.jpf
```

POSIX shell:

```bash
java -ea -jar verification/jpf/.jpf-core/build/RunJPF.jar verification/jpf/threaded-minimal.jpf

java -ea -jar verification/jpf/.jpf-core/build/RunJPF.jar verification/jpf/taskbased-minimal.jpf
```

If you want to run them from the local helper script after cloning and
building `jpf-core`, use:

PowerShell:

```powershell
python assignment-1/verification/jpf/run_jpf.py --model both
```

POSIX shell:

```bash
python assignment-1/verification/jpf/run_jpf.py --model both
```

## 6. What To Record

For the report, record:

- the command used to start the JPF container;
- the Docker command used to build and launch JPF, if you use `--docker`;
- the local `jpf-core` launcher jar used to start JPF;
- the exact JPF invocation for each model;
- whether JPF found a counterexample or completed cleanly;
- the main limitation of the model, if any.

## 7. Practical Verification Notes

- Keep the harness small and deterministic.
- Keep the number of threads or tasks minimal.
- Avoid GUI and the full physics engine.
- Prefer bounded runs so the state space stays manageable.
- If the model explodes, reduce the number of actors before changing the
  properties.
