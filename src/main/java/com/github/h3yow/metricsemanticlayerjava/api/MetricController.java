package com.github.h3yow.metricsemanticlayerjava.api;

import com.github.h3yow.metricsemanticlayerjava.engine.SemanticEngine;
import com.github.h3yow.metricsemanticlayerjava.model.MetricDefinition;
import com.github.h3yow.metricsemanticlayerjava.model.QueryRequest;
import com.github.h3yow.metricsemanticlayerjava.model.QueryResult;
import com.github.h3yow.metricsemanticlayerjava.registry.MetricRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标查询 / 定义管理 HTTP 入口，便于本地手动验证。
 *
 * <ul>
 *   <li>POST /api/metrics/query 执行查询；</li>
 *   <li>POST /api/metrics/register 注册指标新版本（先校验后原子发布）；</li>
 *   <li>POST /api/metrics/{name}/default?version= 切换默认版本；</li>
 *   <li>GET  /api/metrics 查看注册表（版本、默认版本、代际）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricController {

    private final SemanticEngine engine;

    public MetricController(SemanticEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/query")
    public QueryResult query(@RequestBody QueryRequest request) {
        return engine.query(request);
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody MetricDefinition def) {
        engine.registerMetric(def);
        return Map.of("registered", def.name() + "@v" + def.version(),
                "generation", engine.registry().generation());
    }

    @PostMapping("/{name}/default")
    public Map<String, Object> setDefault(@PathVariable String name,
                                          @RequestParam int version) {
        engine.setDefaultVersion(name, version);
        return Map.of("metric", name, "defaultVersion", version,
                "generation", engine.registry().generation());
    }

    @GetMapping
    public Map<String, Object> registry() {
        MetricRegistry.RegistrySnapshot snap = engine.registry().currentSnapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generation", engine.registry().generation());
        Map<String, Object> metrics = new LinkedHashMap<>();
        for (String name : snap.defaultVersions().keySet()) {
            Map<String, Object> info = new LinkedHashMap<>();
            List<Integer> versions = engine.registry().versionsOf(snap, name);
            info.put("versions", versions);
            info.put("defaultVersion", snap.defaultVersions().get(name));
            MetricDefinition d = snap.metric(name, snap.defaultVersions().get(name));
            info.put("description", d.description());
            metrics.put(name, info);
        }
        out.put("metrics", metrics);
        out.put("dimensions", snap.dimensions().keySet());
        out.put("snapshot", engine.dataSnapshot() == null ? null : engine.dataSnapshot().id());
        return out;
    }
}
