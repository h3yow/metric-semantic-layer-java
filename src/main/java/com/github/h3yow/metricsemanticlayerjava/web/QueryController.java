package com.github.h3yow.metricsemanticlayerjava.web;

import com.github.h3yow.metricsemanticlayerjava.engine.MetricSemanticLayer;
import com.github.h3yow.metricsemanticlayerjava.query.Query;
import com.github.h3yow.metricsemanticlayerjava.query.QueryResult;
import com.github.h3yow.metricsemanticlayerjava.query.SortOrder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP surface over the semantic layer. The request body only carries
 * metric names/versions and dimension names; semantic definitions live in the
 * published catalog, never in the request.
 */
@RestController
@RequestMapping("/api/metrics")
public class QueryController {

    private final MetricSemanticLayer layer;

    public QueryController(MetricSemanticLayer layer) {
        this.layer = layer;
    }

    @PostMapping("/query")
    public QueryResult query(@RequestBody QueryRequest req) {
        return layer.query(toQuery(req));
    }

    @PostMapping("/explain")
    public Map<String, Object> explain(@RequestBody QueryRequest req) {
        return layer.explain(toQuery(req));
    }

    @GetMapping("/lineage/{metric}")
    public Object lineage(@PathVariable String metric,
                          @RequestParam(required = false) Integer version) {
        var node = layer.lineage(metric, version);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metric", node.metric());
        out.put("resolvedVersion", node.version());
        out.put("kind", node.kind());
        out.put("detail", node.detail());
        out.put("dependencies", node.dependencies());
        return out;
    }

    private static Query toQuery(QueryRequest req) {
        Query.Builder b = Query.builder();
        for (MetricPart m : req.metrics()) {
            if (m.version() == null) {
                b.metric(m.name());
            } else {
                b.metric(m.name(), m.version());
            }
        }
        if (req.groupBy() != null) {
            req.groupBy().forEach(b::groupBy);
        }
        if (req.orderBy() != null) {
            for (OrderPart o : req.orderBy()) {
                b.orderBy(o.column(), "desc".equalsIgnoreCase(o.direction())
                        ? SortOrder.DESC : SortOrder.ASC);
            }
        }
        if (req.limit() != null) {
            b.limit(req.limit());
        }
        if (req.snapshotId() != null) {
            b.snapshot(req.snapshotId());
        }
        return b.build();
    }

    public record MetricPart(String name, Integer version) {
    }

    public record OrderPart(String column, String direction) {
    }

    public record QueryRequest(List<MetricPart> metrics,
                               List<String> groupBy,
                               List<OrderPart> orderBy,
                               Integer limit,
                               Long snapshotId) {
        public QueryRequest {
            if (metrics == null) {
                metrics = new ArrayList<>();
            }
        }
    }
}
