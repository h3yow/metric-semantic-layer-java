package com.github.h3yow.metricsemanticlayerjava.query;

import java.util.List;

/**
 * One node in the resolved metric dependency trace, recording the exact version
 * that was used. Lets callers audit why a number came out the way it did and
 * prove that no cross-version mixing happened.
 */
public record LineageNode(String metric,
                          int version,
                          String kind,
                          String detail,
                          List<LineageNode> dependencies) {

    public List<LineageNode> dependenciesOrEmpty() {
        return dependencies == null ? List.of() : dependencies;
    }
}
