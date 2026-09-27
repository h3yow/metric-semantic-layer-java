package com.github.h3yow.metricsemanticlayerjava.engine;

import com.github.h3yow.metricsemanticlayerjava.error.InvalidDefinitionException;
import com.github.h3yow.metricsemanticlayerjava.model.DataSnapshot;
import com.github.h3yow.metricsemanticlayerjava.model.DerivedDimension;
import com.github.h3yow.metricsemanticlayerjava.model.MetricDefinition;
import com.github.h3yow.metricsemanticlayerjava.model.QueryRequest;
import com.github.h3yow.metricsemanticlayerjava.model.QueryResult;
import com.github.h3yow.metricsemanticlayerjava.registry.MetricRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 语义层门面。
 *
 * <p>发布语义：所有校验在锁内、在旧快照之上完成，校验失败时注册表保持原状、不发布任何半成品；
 * 校验通过后 registry 以 copy-on-write 原子换用新快照。查询开始时抓取一次
 * （注册表快照, 数据快照）并全程持有，因此口径更新期间不会读到改了一半的定义。</p>
 */
public final class SemanticEngine {

    private static final Logger log = LoggerFactory.getLogger(SemanticEngine.class);

    private final MetricRegistry registry;
    private final DefinitionValidator validator;
    private final QueryPlanner planner;
    private final QueryCache cache;
    private volatile DataSnapshot dataSnapshot;

    public SemanticEngine(MetricRegistry registry,
                          DefinitionValidator validator,
                          QueryPlanner planner,
                          QueryCache cache) {
        this.registry = registry;
        this.validator = validator;
        this.planner = planner;
        this.cache = cache;
    }

    public MetricRegistry registry() {
        return registry;
    }

    public QueryCache cache() {
        return cache;
    }

    public void bindDataSnapshot(DataSnapshot snapshot) {
        this.dataSnapshot = snapshot;
        log.info("绑定数据快照 {}", snapshot.id());
    }

    public DataSnapshot dataSnapshot() {
        return dataSnapshot;
    }

    /** 校验并原子发布一个指标版本，随后校验整张依赖图。 */
    public synchronized void registerMetric(MetricDefinition def) {
        DataSnapshot snap = requireData();
        log.info("注册指标 {}@v{}: table={}, agg={}, source={}, expr={}, filter={}",
                def.name(), def.version(), def.table(), def.agg(),
                def.sourceColumn(), def.expression(), def.filterExpression());
        // 先在当前数据与注册表上下文里做单定义校验
        validator.validateMetric(def, snap);
        // 校验是否重复版本（registry 也会原子拒绝，这里提前给出清晰错误）
        MetricRegistry.RegistrySnapshot current = registry.currentSnapshot();
        if (current.metrics().containsKey(
                new MetricRegistry.MetricKey(def.name(), def.version()))) {
            throw new InvalidDefinitionException(
                    "指标版本已存在，重复注册被拒绝: " + def.name() + "@v" + def.version());
        }
        // 原子发布（单定义已校验：未知字段/类型/自引用/结构非法）
        registry.register(def);
        log.info("指标 {}@v{} 发布成功，注册表代际={}，默认版本={}",
                def.name(), def.version(), registry.generation(),
                registry.currentSnapshot().defaultVersions().get(def.name()));
    }

    /**
     * 显式全量校验整张指标依赖图（缺失依赖版本、环等）。
     * 注册单个指标时不强制，以允许前向引用；查询时会惰性校验实际触达的依赖闭包。
     */
    public void validateAll() {
        validator.validateDependencyGraph(registry, requireData());
    }

    /** 校验并原子发布派生维度。 */
    public synchronized void registerDimension(DerivedDimension dim) {
        DataSnapshot snap = requireData();
        log.info("注册派生维度 {} on {}: {} -> {}", dim.name(), dim.table(),
                dim.expression(), dim.resultType());
        validator.validateDimension(dim, snap);
        registry.registerDimension(dim);
        log.info("派生维度 {} 发布成功，注册表代际={}", dim.name(), registry.generation());
    }

    /** 原子切换默认版本（会做存在性校验）。 */
    public synchronized void setDefaultVersion(String metricName, int version) {
        log.info("切换默认版本 {} -> v{}", metricName, version);
        registry.setDefaultVersion(metricName, version);
        log.info("默认版本已切换，注册表代际={}", registry.generation());
    }

    /**
     * 执行查询：抓取一致快照 → 查缓存 → 未命中则执行并回填。
     * 整个查询持有同一对快照，重复 / 并发查询结果一致。
     */
    public QueryResult query(QueryRequest request) {
        DataSnapshot data = requireData();
        MetricRegistry.RegistrySnapshot regSnap = registry.currentSnapshot();
        long generation = registry.generation();

        QueryCache.CacheKey key = QueryCache.keyOf(request, data.id(), generation);
        QueryResult cached = cache.get(key);
        if (cached != null) {
            log.info("查询命中缓存 metric={} 输入版本={} snapshot={} 代际={} 命中版本=v{}",
                    request.metricName(),
                    request.metricVersion() == null ? "默认" : ("v" + request.metricVersion()),
                    data.id(), generation, cached.metricVersion());
            return cached;
        }

        QueryResult result = planner.execute(request, regSnap, data);
        cache.put(key, result);
        log.info("查询未命中缓存，已求值并回填 metric={} 命中版本=v{} 分组行数={} 耗时={}ms",
                request.metricName(), result.metricVersion(),
                result.rows().size(), result.elapsedMillis());
        return result;
    }

    private DataSnapshot requireData() {
        DataSnapshot s = dataSnapshot;
        if (s == null) {
            throw new InvalidDefinitionException("尚未绑定数据快照，请先 bindDataSnapshot");
        }
        return s;
    }
}
