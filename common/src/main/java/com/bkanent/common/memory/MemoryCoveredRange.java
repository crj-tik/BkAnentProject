package com.bkanent.common.memory;

import java.io.Serializable;

/** Inclusive nonempty conversation sequence range. Shape alone does not prove durable coverage. */
public record MemoryCoveredRange(long fromSequence, long throughSequence) implements Serializable {
    private static final long serialVersionUID = 1L;
    public MemoryCoveredRange {
        MemoryContract.require(fromSequence > 0 && throughSequence >= fromSequence, MemoryException.Field.COVERAGE);
    }
    public boolean contains(long sequence) { return sequence >= fromSequence && sequence <= throughSequence; }
}
