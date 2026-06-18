# Controller package

This directory contains command-based active-controller abstractions reused
from the course sketches.

## Purpose

The package provides a generic producer/consumer style controller that can
serialize commands on a target object. In the current project it is mainly kept
as reusable reference infrastructure and as a trace of the architectural path
that led to the threaded runtime.

## Files

- `Cmd.java`
  Generic command interface. A command knows how to execute an action on a
  target object.
- `ActiveController.java`
  Thread-based controller that consumes `Cmd<T>` objects from a bounded buffer
  and executes them on its owned target.

## Relationships

- Uses `model.concurrent.BoundedBuffer` and `BoundedBufferImpl`.
- Represents the same high-level idea used in the threaded runtime:
  asynchronous producers submit requests, while one active component serializes
  state mutation.
- The playable threaded implementation uses the specialized package
  `pcd.poool.threaded` instead of this generic controller directly.
