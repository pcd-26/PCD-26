PCD a.y. 2024-2025 - ISI LM UNIBO - Cesena Campus

# Sketch 02 - Notes

v1.0.0-20260320

This sketch (`pcd.sketch02`) shows an example about how to manage **asynchronous inputs** from the keyboard in a concurrent program with a GUI, applying the **MVC** architectural pattern.
- The Model (package `pcd.sketch02.model`) includes:
  - a passive `Counter` object, which is implemented as a monitor
    - the **Observer pattern** is adopted (following MVC), so that this object is a source of events that are observed by observer objects (also called listeners, in the Java world)
    - `CounterObserver` is the interface that must be implemented by observers
    - **FOCUS POINT**: the pattern is implemented as in sequential programs: observers are notified through a synchronous method call. Being the source in this case a monitor, the call is done without releasing the lock on the monitor 
  - an active component `AutonomousUpdater` that periodically updates the count
- The View (package `pcd.sketch02.view`) includes a simple GUI, visualizing the current count value and a simple graphical representation (as a analog clock)
  - following the MVC pattern, the View is an observer of the update events notified by the Model (count object in this case)
  - the View in this case plays also the role of input source for the Controller in the MVC pattern
    - the `ViewFrame` uses the Java Swing API to listen key events, notifying them to the Controller as commands   
- The Controller (package `pcd.sketch02.controller`) is implemented by the active component `ActiveController`, which reacts to asynchronous input representing requests/commands from the user and executes them, acting on the Model (the counter)
  - `Command` design pattern is adopted
    - `Cmd` is the command interface
    - `IncCmd` models a request to increment the counter, `ResetCmd` to reset the counter
  - A producer/consumer architecture is implemented
    - the active controller consumes input produced by the input components (the view in this case), by means of a bounded buffer
  - **FOCUS POINT**: in this example, `ActiveController` is blocked waiting for commands. Asychronous variants can be adopted when the controller must have a looping behaviour and cannot block (like in game loops)
    - in this case, the bounded buffer could be designed to provide also a non-blocking variant of the `get`, often called `poll`


