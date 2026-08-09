# View package

This directory contains the Swing rendering layer and the shared rendering
state used by the launchers.

## What To Say At The Oral

- The view is a boundary layer, not the game logic.
- The runtime owns the physics and game rules; the EDT only paints copied
  state.
- `ViewModel` acts as a thread-safe handoff object between the runtime and
  Swing.
- `RenderSynch` keeps repaint requests and completed frames ordered.
- User input becomes callbacks, so the UI never reaches into the model
  directly.

## Files

- `RenderSynch.java`
  Synchronization helper that blocks the runtime until the requested frame has
  been painted.

## Subdirectory `board`

- `View.java`
  Small facade used by the launchers. It creates the frame on the EDT and
  exposes a simple `render()` method.
- `ViewFrame.java`
  Main Swing window. Handles painting, keyboard shots, mouse drag shots,
  restart input, countdown overlays, and HUD rendering.
- `ViewModel.java`
  Thread-safe rendering state shared between the runtime and Swing. It stores
  copied board data, logical snapshots, and shot-preview information.

## Relationships

- `SequentialPoool` and `ThreadedPoool` both create a `View` and keep updating
  its `ViewModel`.
- `ViewFrame` never mutates `Board` or `GameModel` directly.
- Rendering uses copied or immutable data so Swing does not read the mutable
  game model directly.
- Input is converted into callbacks such as "shoot human" or "restart".
