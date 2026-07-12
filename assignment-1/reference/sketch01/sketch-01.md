PCD a.y. 2024-2025 - ISI LM UNIBO - Cesena Campus

# Sketch 01 - Notes

v1.0.0-20260320

This sketch (`pcd.sketch01`) shows an example of a board, with a number of small balls and one player ball moving and bouncing. The behaviour of the program is governed by a `main loop`, repeatedly computing the state of the board and rendering a new frame.

### About the Main Loop

At each cycle, the main loop:
- Checks if it is time to kick the player ball
- Computes the next board state, depending on how much time is elapsed
- Updates the view model with the updated board state
- Renders a new frame, updating synchronously the view with the updated view model

The board state involves a sequence of steps:
- Updating the state of the player ball and of each small balls
  - the velocity is updated by applying the friction factor
  - the position is updated given the velocity and the elapsed time
  - collision with boundaries is checked
- Then, collisions among small balls are checked
  - the check is done by a nested loop, checking each body with all other bodies (but only once)
- Finally, collision between the player ball and small balls is checked

### About Physics

The program implements simple elastic collisions between bodies with a certain mass and radius. Some parameters that can be adjusted in order to refine behaviours:
- `FRICTION_FACTOR` (in `Ball` class) is a static parameter that determines how much friction do we want: 0 is no friction, 1 is a quite strong friction
- `RESTITUTION_FACTOR` (in `Ball` class) is a static parameter that determines how much elastic should be collisions: 1 is fully elastic
- The body masses influence collisions and how the generated impulses is distributed among bodies. So they can be calibrated to refine the wanted behaviour.

### About Rendering

Rendering is requested by the thread running the main loop when calling `view.render`.  
- Painting is performed by the EDT (i.e. the Swing thread), asynchronously, when a `repaint` Swing method is requested (on a frame or a panel or any Swing component) 
- If painting is done while the view model is udpated, we can have races
- To avoid this, `view.render` is implemented with a synchronous behaviour, so that it returns only when the rendering has been completed, before computing the next state and view model 
  - the solution adopts a monitor (`RenderSynch`), used to synchronize the EDT with the thread requesting repainting
  - more efficient approaches can be adopted, such as [double buffering](https://en.wikipedia.org/wiki/Multiple_buffering)

### About Scale

Three different board configurations represented by the class `BoardConf` can be tried:
- minimal: 2 small balls 
- large: 400 small balls
- massive: 4500 small balls

The configutation is set when configuring the board, before starting the simulation loop. 

**FOCUS POINT**: the minimal and large configuration typically shows a good frame rate - higher than 25 frame per second (fps) - with current PCs. Instead, the massive configuration in this implementation typically has a poor frame rate (less that 20 fps). A concurrent version of the program could be useful to improve this.


