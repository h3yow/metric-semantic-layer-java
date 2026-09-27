package com.github.h3yow.metricsemanticlayerjava.local;

import com.github.h3yow.metricsemanticlayerjava.engine.MetricSemanticLayer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires a local, fully in-memory {@link MetricSemanticLayer} bean with the demo
 * snapshot and the revision-2 catalog (revenue default = v2).
 */
@Configuration
public class DemoSemanticLayerConfig {

    public static final long SNAPSHOT_ID = 1L;
    public static final long ROW_COUNT = 1_000L;

    @Bean
    public MetricSemanticLayer metricSemanticLayer() {
        MetricSemanticLayer layer = new MetricSemanticLayer();
        var snapshot = LocalTables.snapshot(SNAPSHOT_ID, ROW_COUNT);
        layer.publishSnapshot(snapshot);
        layer.publishCatalog(LocalDemoCatalog.revision2(snapshot.tables()));
        return layer;
    }
}
