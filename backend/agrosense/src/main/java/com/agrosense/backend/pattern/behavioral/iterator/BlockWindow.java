package com.agrosense.backend.pattern.behavioral.iterator;

import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32Reading;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Function;

/**
 * Iterator, the collection: the blocks of one aggregation window. Who combines them walks them with
 * {@link #iterator()} or, one field at a time, with {@link #values}, and never sees how they are kept.
 */
public final class BlockWindow implements Iterable<Esp32Reading> {

    private Esp32Reading[] blocks = new Esp32Reading[64];
    private int size;

    public void add(Esp32Reading block) {
        if (size == blocks.length) {
            blocks = Arrays.copyOf(blocks, size * 2);
        }
        blocks[size++] = block;
    }

    public int size() {
        return size;
    }

    public void clear() {
        Arrays.fill(blocks, 0, size, null);
        size = 0;
    }

    @Override
    public Iterator<Esp32Reading> iterator() {
        return new BlockIterator();
    }

    /** The values one field took in the window, oldest first, skipping the blocks that did not measure it. */
    public FieldIterator values(Function<Esp32Reading, Double> field) {
        return new FieldIterator(iterator(), field);
    }

    private final class BlockIterator implements Iterator<Esp32Reading> {

        private int next;

        @Override
        public boolean hasNext() {
            return next < size;
        }

        @Override
        public Esp32Reading next() {
            if (!hasNext()) {
                throw new NoSuchElementException("The window has no more blocks");
            }
            return blocks[next++];
        }
    }
}
