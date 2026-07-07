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

Inside that container, JPF can be built from source with the usual Gradle
build.

The JUnit integration test expects a runtime classpath that exposes the JPF
launcher class. Provide it through:

- `JPF_CP`

If your local setup uses a different launcher class, override it with:

- `JPF_LAUNCHER`

## 2. Compile The Minimal Harnesses

The harnesses live under:

- `assignment-1/verification/jpf/src/pcd/poool/verification/jpf`

They are compiled by the JUnit integration test into:

- `assignment-1/target/jpf-classes`

The important point is that the resulting classpath must include only the
minimal harness classes and the JPF runtime, not the full application build.

The integration test compiles the harnesses automatically into:

- `assignment-1/target/jpf-classes`

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

Example invocation:

```bash
JPF_CP="<classpath that exposes gov.nasa.jpf.tool.RunJPF>" \
  mvn -f assignment-1/pom.xml \
  -Djpf.cp="$JPF_CP" \
  -Dtest=pcd.poool.verification.JpfVerificationArtifactsTest \
  test
```

If you need to override the launcher class:

```bash
JPF_CP="<classpath that exposes gov.nasa.jpf.tool.RunJPF>" \
  mvn -f assignment-1/pom.xml \
  -Djpf.cp="$JPF_CP" \
  -Djpf.launcher=gov.nasa.jpf.tool.RunJPF \
  -Dtest=pcd.poool.verification.JpfVerificationArtifactsTest \
  test
```

## 6. What To Record

For the report, record:

- the command used to start the JPF container;
- the JPF runtime classpath passed through `JPF_CP` or `-Djpf.cp`;
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
