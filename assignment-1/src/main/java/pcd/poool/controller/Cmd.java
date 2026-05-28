package pcd.poool.controller;

/**
 * Generic command contract consumed by an {@link ActiveController}.
 *
 * <p>A command represents a deferred request: producers create command objects
 * and enqueue them, while the active controller later executes them on its
 * target object. This lets asynchronous components communicate through
 * messages instead of modifying shared model state directly.
 *
 * @param <T> target state/service type that the command updates
 */
public interface Cmd<T> {

    /**
     * Applies this command to the controller target.
     *
     * @param target object owned/controlled by the active controller
     */
    void execute(T target);
}
