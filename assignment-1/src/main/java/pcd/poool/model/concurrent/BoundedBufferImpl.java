package pcd.poool.model.concurrent;

import java.util.LinkedList;

/**
 * Simple monitor-based bounded buffer implementation.
 *
 * @param <Item> buffered item type
 */
public class BoundedBufferImpl<Item> implements BoundedBuffer<Item> {

	private LinkedList<Item> buffer;
	private int maxSize;

	/**
	 * Creates a bounded buffer.
	 *
	 * @param size maximum number of items that can be stored
	 */
	public BoundedBufferImpl(int size) {
		if (size <= 0) {
			throw new IllegalArgumentException("size must be > 0");
		}
		buffer = new LinkedList<Item>();
		maxSize = size;
	}

	@Override
	public synchronized void put(Item item) throws InterruptedException {
		while (isFull()) {
			wait();
		}
		buffer.addLast(item);
		notifyAll();
	}

	@Override
	public synchronized Item get() throws InterruptedException {
		while (isEmpty()) {
			wait();
		}
		Item item = buffer.removeFirst();
		notifyAll();
		return item;
	}

	@Override
	public synchronized Item poll() {
		if (isEmpty()) {
			return null;
		}
		Item item = buffer.removeFirst();
		notifyAll();
		return item;
	}

	private boolean isFull() {
		return buffer.size() == maxSize;
	}

	private boolean isEmpty() {
		return buffer.size() == 0;
	}
}
