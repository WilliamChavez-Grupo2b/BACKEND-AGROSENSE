package com.agrosense.backend.pattern.behavioral.iterator;

import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32Reading;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Function;

/**
 * Iterator: one field of the window's blocks. It looks one block ahead to skip those where the field is missing (a
 * "nan" from the DHT11), so whoever combines the values never has to check for them.
 */
public final class FieldIterator implements Iterator<Double> {

    private final Iterator<Esp32Reading> blocks;
    private final Function<Esp32Reading, Double> field;
    private Double ahead;

    FieldIterator(Iterator<Esp32Reading> blocks, Function<Esp32Reading, Double> field) {
        this.blocks = blocks;
        this.field = field;
        advance();
    }

    @Override
    public boolean hasNext() {
        return ahead != null;
    }

    @Override
    public Double next() {
        if (ahead == null) {
            throw new NoSuchElementException("The field has no more values in this window");
        }
        Double value = ahead;
        advance();
        return value;
    }

    private void advance() {
        ahead = null;
        while (ahead == null && blocks.hasNext()) {
            ahead = field.apply(blocks.next());
        }
    }
}
