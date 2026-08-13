# Repository Guidelines

## Scope

These guidelines apply to the whole repository. More specific instructions in
subdirectories may extend or override them.

When working on a specific assignment or subproject, limit repository exploration
to the relevant directory unless another part of the repository is explicitly
required.

For example, when a task concerns `assignment-2`, prefer working only inside
`assignment-2` and directly related shared files. Do not inspect `assignment-1`,
`assignment-3`, `assignment-4`, reports, documentation, benchmarks, or other
unrelated directories unless they are necessary to complete the requested task.

Avoid broad repository scans when the target scope is already known. Prefer
targeted file searches and reads to reduce unnecessary context usage and keep the
working context focused.

## Testing Policy

Every functional update must include focused tests in the same change.

Examples:

* new model class: add unit tests for its core behavior
* changed physics rule: add or update physics tests
* new controller/command behavior: add controller or command tests
* new benchmark or CLI entry point: add at least compilation coverage and, when
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

## Documentation Policy

Keep documentation aligned with the code.

* Update README, architecture notes, TODO lists, or scope documents whenever a
  code change affects behavior, structure, commands, execution modes, or
  delivery scope.
* Add or update Javadocs for public APIs when the contract is not obvious from
  the signature.
* Add code comments only where they clarify non-trivial decisions, algorithms,
  synchronization assumptions, numerical choices, or domain rules.
* Avoid comments that merely repeat what the code says.
* When a change does not require documentation updates, mention why in the final
  response if the reason is not obvious.

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

## Performance Gate

When working on `assignment-1` performance changes, use the compact speedup
benchmark as the default before/after comparison:

```bash
python scripts/run_benchmarks.py --mode speedup
```

Treat the generated `benchmarks/results/aggregated-results.csv` and
`benchmarks/results/speedup-results.csv` as the canonical comparison point for
engine changes. Prefer the five canonical workload sizes in that mode, compare
the same machine/JVM pair, and judge a change by the median elapsed time and
speedup for each scenario.

## Design Preferences

* Keep the domain model and physics engine independent from execution strategy.
* Add separate runners for sequential, platform-thread, and task-based versions.
* Prefer immutable value objects for mathematical data such as points and
  vectors.
* Keep model mutations serialized unless a subsystem is explicitly designed and
  tested for safe parallelism.
* When adding concurrency, document ownership of shared state and the intended
  synchronization strategy.

## Engineering Quality

Maintain high software engineering quality throughout the project.

* Avoid magic strings and unexplained magic numbers. Use named constants,
  enums, value objects, or configuration objects when a literal carries domain
  meaning.
* Apply SOLID principles where they improve clarity and maintainability.
* Follow the Single Responsibility Principle: each class/module should have one
  clear reason to change.
* Follow DRY: remove meaningful duplication, but do not introduce abstractions
  before they make the code simpler.
* Prefer explicit domain names over vague names such as `data`, `manager`, or
  `handler` when a more precise concept exists.
* Keep public APIs small, intentional, and documented when their use is not
  obvious.
* Preserve deterministic behavior in physics and tests unless randomness is
  explicitly part of the requirement and controlled by a seed.

