package pcd.poool.threaded;

/**
 * Monitor-based completion receipt for commands submitted to the threaded
 * game runner.
 *
 * @param <T> command result type
 */
public class CommandReceipt<T> extends pcd.poool.runtime.CommandReceiptSupport<T> {

    /**
     * Creates an incomplete command receipt.
     */
    CommandReceipt() {
    }
}
