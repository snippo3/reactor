package reactor.core.alloc;

import reactor.function.Supplier;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * An implementation of {@link reactor.core.alloc.Allocator} that uses reference counting to determine when an object
 * should
 * be recycled and placed back into the pool to be reused.
 *
 * @author Jon Brisbin
 * @since 1.1
 */
public class ReferenceCountingAllocator<T extends Recyclable> implements Allocator<T> {

	private static final int DEFAULT_INITIAL_SIZE = 2048;

	private final Object                  refMonitor = new Object();
	private final ArrayList<Reference<T>> references = new ArrayList<Reference<T>>();
	private final Supplier<T> factory;
	private final BitSet      leaseMask;

	public ReferenceCountingAllocator(Supplier<T> factory) {
		this(DEFAULT_INITIAL_SIZE, factory);
	}

	public ReferenceCountingAllocator(int initialSize, Supplier<T> factory) {
		this.factory = factory;
		this.references.ensureCapacity(initialSize);
		this.leaseMask = new BitSet(initialSize);
		expand(initialSize);
	}

	@Override
	public Reference<T> allocate() {
		Reference<T> ref;
		int len = refCnt();
		int next;

		synchronized(leaseMask) {
			next = leaseMask.nextClearBit(0);
			if(next >= len) {
				expand(len);
			}
			leaseMask.set(next);
		}

		if(next < 0) {
			throw new RuntimeException("Allocator is exhausted.");
		}

		ref = references.get(next);
		if(null == ref) {
			// this reference has been nulled somehow.
			// that's not really critical, just replace it.
			synchronized(refMonitor) {
				ref = new ReferenceCountingAllocatorReference<T>(factory.get(), next);
				references.set(next, ref);
			}
		}
		ref.retain();

		return ref;
	}

	@Override
	public List<Reference<T>> allocateBatch(int size) {
		List<Reference<T>> refs = new ArrayList<Reference<T>>(size);
		for(int i = 0; i < size; i++) {
			refs.add(allocate());
		}
		return refs;
	}

	@Override
	public void release(List<Reference<T>> batch) {
		if(null != batch && !batch.isEmpty()) {
			for(Reference<T> ref : batch) {
				ref.release();
			}
		}
	}

	private int refCnt() {
		synchronized(refMonitor) {
			return references.size();
		}
	}

	private void expand(int num) {
		synchronized(refMonitor) {
			int len = references.size();
			int newLen = len + num;
			for(int i = len; i <= newLen; i++) {
				references.add(new ReferenceCountingAllocatorReference<T>(factory.get(), i));
			}
		}
	}

	private class ReferenceCountingAllocatorReference<T extends Recyclable> extends AbstractReference<T> {
		private final int bit;

		private ReferenceCountingAllocatorReference(T obj, int bit) {
			super(obj);
			this.bit = bit;
		}

		@Override
		public void release(int decr) {
			super.release(decr);
			if(getReferenceCount() < 1) {
				// There won't be contention to clear this
				leaseMask.clear(bit);
			}
		}
	}

}