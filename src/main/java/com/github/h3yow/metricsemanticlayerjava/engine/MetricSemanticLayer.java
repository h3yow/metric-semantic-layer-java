package com.github.h3yow.metricsemanticlayerjava.engine;

import com.github.h3yow.metricsemanticlayerjava.definition.Catalog;
import com.github.h3yow.metricsemanticlayerjava.exception.InvalidDefinitionException;
import com.github.h3yow.metricsemanticlayerjava.model.DataSnapshot;
import com.github.h3yow.metricsemanticlayerjava.query.LineageNode;
import com.github.h3yow.metricsemanticlayerjava.query.Query;
import com.github.h3yow.metricsemanticlayerjava.query.QueryResult;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Entry point of the local metric semantic layer.
 *
 * <p>Concurrency model:
 * <ul>
 *   <li>catalogs and data snapshots are both immutable and published atomically
 *       through an {@link AtomicReference} holding one immutable {@link Generation};</li>
 *   <li>a query reads the generation reference exactly once, so it can never see a
 *       half-updated catalog or a mix of two snapshots;</li>
 *   <li>republishing a catalog or snapshot swaps in a brand-new {@link QueryEngine}
 *       with fresh memoization caches — old engines (and in-flight queries) keep
 *       serving the generation they pinned, and the new engine can never return a
 *       value computed against stale data;</li>
 *   <li>a query may pin a snapshot id; ids older than the current snapshot are
 *       rejected rather than answered with possibly-stale data.</li>
 * </ul>
 */
public final class MetricSemanticLayer {

    /** One consistent (catalog + data) generation. */
    private record Generation(Catalog catalog, DataSnapshot snapshot, QueryEngine engine) {
    }

    private final AtomicReference<Generation> current = new AtomicReference<>();
    private final boolean allowOldSnapshots;

    public MetricSemanticLayer() {
        this(false);
    }

    /**
     * @param allowOldSnapshots if false (recommended), querying a snapshot id that
     *                          is not the latest published one fails fast, so callers
     *                          never unknowingly consume expired results.
     */
    public MetricSemanticLayer(boolean allowOldSnapshots) {
        this.allowOldSnapshots = allowOldSnapshots;
    }

    public synchronized void publishSnapshot(DataSnapshot snapshot) {
        Generation gen = current.get();
        Catalog catalog = gen == null ? null : gen.catalog();
        if (catalog == null) {
            current.set(new Generation(null, snapshot, null));
            return;
        }
        current.set(new Generation(catalog, snapshot, new QueryEngine(catalog, snapshot)));
    }

    public synchronized void publishCatalog(Catalog catalog) {
        Generation gen = current.get();
        DataSnapshot snapshot = gen == null ? null : gen.snapshot();
        if (snapshot == null) {
            current.set(new Generation(catalog, null, null));
        } else {
            current.set(new Generation(catalog, snapshot, new QueryEngine(catalog, snapshot)));
        }
    }

    public DataSnapshot currentSnapshot() {
        Generation g = current.get();
        if (g == null || g.snapshot() == null) {
            throw new IllegalStateException("no data snapshot has been published yet");
        }
        return g.snapshot();
    }

    public Catalog currentCatalog() {
        Generation g = current.get();
        if (g == null || g.catalog() == null) {
            throw new IllegalStateException("no metric catalog has been published yet");
        }
        return g.catalog();
    }

    public QueryResult query(Query query) {
        return pin(query).engine().execute(query);
    }

    public Map<String, Object> explain(Query query) {
        return pin(query).engine().explain(query);
    }

    public LineageNode lineage(String metric, Integer version) {
        return ready().engine().lineage(metric, version);
    }

    private Generation pin(Query query) {
        Generation gen = ready();
        if (query.snapshotId() != null
                && query.snapshotId() != gen.snapshot().id()
                && !allowOldSnapshots) {
            throw new InvalidDefinitionException(
                    "snapshot id " + query.snapshotId() + " is no longer current (current="
                            + gen.snapshot().id() + "): refusing to return stale results");
        }
        return gen;
    }

    private Generation ready() {
        Generation gen = current.get();
        if (gen == null || gen.catalog() == null || gen.snapshot() == null
                || gen.engine() == null) {
            throw new IllegalStateException(
                    "semantic layer is not ready: publish both a catalog and a data snapshot first");
        }
        return gen;
    }
}
