package pcd.poool.controller;

/**
 * Generic command contract consumed by the active controller.
 *
 * @param <T> target state/service type that the command updates
 */
public interface Cmd<T> {

    void execute(T target);
}
