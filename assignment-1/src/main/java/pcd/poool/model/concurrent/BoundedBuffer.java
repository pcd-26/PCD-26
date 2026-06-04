package pcd.poool.model.concurrent;

/**
 * Minimal blocking bounded buffer interface for producer/consumer coordination.
 *
 * @param <Item> buffered item type
 */
public interface BoundedBuffer<Item> {

    /**
     * Inserts an item, waiting while the buffer is full.
     *
     * @param item item to insert
     * @throws InterruptedException if interrupted while waiting for capacity
     */
    void put(Item item) throws InterruptedException;
    
    /**
     * Removes an item, waiting while the buffer is empty.
     *
     * @return removed item
     * @throws InterruptedException if interrupted while waiting for data
     */
    Item get() throws InterruptedException;

    /**
     * Attempts to remove an item without blocking.
     *
     * @return removed item, or {@code null} when the buffer is empty
     */
    Item poll();
    
}
