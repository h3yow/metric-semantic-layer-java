package com.github.h3yow.metricsemanticlayerjava.error;

import java.util.List;

/**
 * 指标间依赖存在环。环路径会被记录以便排查。
 */
public final class CircularDependencyException extends DefinitionException {
    private final List<String> cycle;

    public CircularDependencyException(List<String> cycle) {
        super("检测到循环依赖: " + String.join(" -> ", cycle));
        this.cycle = List.copyOf(cycle);
    }

    public List<String> cycle() {
        return cycle;
    }
}
