package com.github.h3yow.metricsemanticlayerjava.query;

import java.util.List;
import java.util.Map;

/**
 * Result of one query execution.
 *
 * @param columns      output column names in stable order (dimensions first, then metrics)
 * @param rows         result rows, keyed by column name
 * @param snapshotId   exact data snapshot that was read
 * @param catalogRevision catalog revision that was used
 * @param versions     requested metric name -> concrete version that served it
 * @param lineage      fully resolved dependency trace per requested metric
 * @param evaluatedAtMillis wall-clock time at which the plan was bound to its snapshot
 */
public record QueryResult(List<String> columns,
                          List<Map<String, Object>> rows,
                          long snapshotId,
                          long catalogRevision,
                          Map<String, Integer> versions,
                          Map<String, LineageNode> lineage,
                          long evaluatedAtMillis) {
}
