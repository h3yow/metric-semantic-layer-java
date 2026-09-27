package com.github.h3yow.metricsemanticlayerjava.definition;

/**
 * An explicitly versioned pointer from one metric to another.
 *
 * <p>Versions are always explicit on references: a derived metric must compile
 * against one concrete version of every dependency, so republishing the default
 * version of a dependency can never silently change an already-defined metric.
 */
public record MetricRef(String metric, int version) {

    public MetricRef {
        if (metric == null || metric.isBlank()) {
            throw new IllegalArgumentException("referenced metric name must not be blank");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("referenced version must be positive, got " + version);
        }
    }

    public String qualified() {
        return metric + "[v" + version + "]";
    }
}
