package com.agrosense.backend.pattern.behavioral.command;

/**
 * Command: one watch over the field (silent devices, frost, irrigation results) as an object. FieldWatchInvoker runs
 * it and keeps its history without knowing what it does; the scheduler only decides when.
 */
public interface FieldWatchCommand {

    String name();

    void execute();
}
