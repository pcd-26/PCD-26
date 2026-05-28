package pcd.poool.model.concurrent;

/**
 * Minimal blocking bounded buffer interface for producer/consumer coordination.
 */
public interface BoundedBuffer<Item> {

    void put(Item item) throws InterruptedException;
    
    Item get() throws InterruptedException;

    Item poll();
    
}
