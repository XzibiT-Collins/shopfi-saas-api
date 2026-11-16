package com.example.perfume_budget.service;

import java.util.function.Supplier;

/** Unit-test stand-in for {@link DbLockTimeout}: runs the action without touching a database session. */
public class PassThroughLockTimeout extends DbLockTimeout {
    @Override
    public <T> T run(String busyCode, String busyMessage, Supplier<T> action) {
        return action.get();
    }
}
