# View package

This directory contains the Swing rendering layer and the shared rendering
state used by the launchers.

## Purpose

The view package is responsible for:

- creating the Swing window on the EDT;
- rendering the current copied snapshot of the game;
- translating keyboard and mouse input into callbacks;
- keeping rendering separate from the authoritative mutable model.

## Files

- `RenderSynch.java`
  Helper used to coordinate repaint completion with the runtime loop.

## Subdirectory `board`

- `View.java`
  Small facade used by the launchers. It creates the frame and exposes a
  simple `render()` method.
- `ViewFrame.java`
  Main Swing window. Handles painting, keyboard shots, mouse drag shots,
  restart input, and HUD rendering.
- `ViewModel.java`
  Thread-safe rendering state shared between the runtime and Swing. It stores
  copied board data, logical snapshots, and shot-preview information.

## Relationships

- `SequentialPoool` and `ThreadedPoool` both create a `View` and keep updating
  its `ViewModel`.
- `ViewFrame` never mutates `Board` or `GameModel` directly.
- Input is converted into callbacks such as "shoot human" or "restart".
- Rendering uses copied or immutable data so Swing does not read the mutable
  game model directly.
