# Repository Guidelines

## Scope

These guidelines apply to the whole repository. More specific instructions in
subdirectories may extend or override them.

## Testing Policy

Every functional update must include focused tests in the same change.

Examples:

- new model class: add unit tests for its core behavior
- changed physics rule: add or update physics tests
- new controller/command behavior: add controller or command tests
- new benchmark or CLI entry point: add at least compilation coverage and, when
  useful, a small execution test or documented manual command

If a change does not need tests, explain why in the final response. Acceptable
examples are documentation-only edits, comments-only edits, or mechanical
formatting with no behavior change.

For `assignment-1`, tests should live under:

```text
assignment-1/src/test/java
```

Run the Maven test suite before considering a code change complete:

```bash
mvn -f assignment-1/pom.xml test
```

## Assignment 1 Structure

Keep final-delivery code under:

```text
assignment-1/src/main/java/pcd/poool
```

Keep reference sketches under:

```text
assignment-1/reference
```

Do not move sketch/demo code into the final package unless it is intentionally
adapted, tested, and documented as part of the delivery scope.

## Design Preferences

- Keep the domain model and physics engine independent from execution strategy.
- Add separate runners for sequential, platform-thread, and task-based versions.
- Prefer immutable value objects for mathematical data such as points and
  vectors.
- Keep model mutations serialized unless a subsystem is explicitly designed and
  tested for safe parallelism.
- When adding concurrency, document ownership of shared state and the intended
  synchronization strategy.

