package pcd.poool.controller;

/**
 * Command executed by an {@link ActiveController}.
 *
 * @param <T> target type updated by the command
 */
public interface Cmd<T> {

    void execute(T target);
}
