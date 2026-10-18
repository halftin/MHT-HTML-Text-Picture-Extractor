package com.mhtextractor.uicore;

import java.util.function.Consumer;

/** 顺序询问：true 放行，false 中止且不询问后续门。 */
@FunctionalInterface
public interface PreStartGate {
    void ask(Consumer<Boolean> proceed);
}
