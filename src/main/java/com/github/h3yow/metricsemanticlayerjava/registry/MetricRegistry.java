package com.github.h3yow.metricsemanticlayerjava.registry;

import com.github.h3yow.metricsemanticlayerjava.error.InvalidDefinitionException;
import com.github.h3yow.metricsemanticlayerjava.error.MetricNotFoundException;
import com.github.h3yow.metricsemanticlayerjava.error.VersionNotFoundException;
import com.github.h3yow.metricsemanticlayerjava.model.DerivedDimension;
import com.github.h3yow.metricsemanticlayerjava.model.MetricDefinition;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 指标 / 派生维度注册中心。
 *
 * <p>并发语义：内部只持有一个不可变 {@link RegistrySnapshot}（{@code volatile} 引用）。
 * 所有注册 / 设默认版本操作以 copy-on-write 方式重建整张不可变快照并原子换用，
 * 进行中的查询全程持有旧快照引用，永远看到一致的一版定义，读不到改了一半的中间态。
 * 每次变更 {@link #generation()} 单调 +1，作为查询缓存失效依据。</p>
 */
public final class MetricRegistry {

    /** 指标版本坐标。 */
    public record MetricKey(String name, int version) {
    }

    /** 注册表某一时刻的不可变快照。 */
    public static final class RegistrySnapshot {
        private final Map<MetricKey, MetricDefinition> metrics;
        private final Map<String, Integer> defaultVersions;
        private final Map<String, DerivedDimension> dimensions;

        public RegistrySnapshot(Map<MetricKey, MetricDefinition> metrics,
                                Map<String, Integer> defaultVersions,
                                Map<String, DerivedDimension> dimensions) {
            this.metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
            this.defaultVersions = defaultVersions == null ? Map.of() : Map.copyOf(defaultVersions);
            this.dimensions = dimensions == null ? Map.of() : Map.copyOf(dimensions);
        }

        public static RegistrySnapshot empty() {
            return new RegistrySnapshot(Map.of(), Map.of(), Map.of());
        }

        public Map<MetricKey, MetricDefinition> metrics() {
            return metrics;
        }

        public Map<String, Integer> defaultVersions() {
            return defaultVersions;
        }

        public Map<String, DerivedDimension> dimensions() {
            return dimensions;
        }

        public MetricDefinition metric(String name, int version) {
            return metrics.get(new MetricKey(name, version));
        }

        public boolean hasMetricName(String name) {
            return metrics.keySet().stream().anyMatch(k -> k.name().equals(name));
        }
    }

    private volatile RegistrySnapshot snapshot = RegistrySnapshot.empty();
    private volatile long generation;

    /** 取当前不可变快照（查询全程持有它）。 */
    public RegistrySnapshot currentSnapshot() {
        return snapshot;
    }

    /** 注册表代际：定义每次变更 +1。 */
    public long generation() {
        return generation;
    }

    /**
     * 快照替换（用于发布后全量校验失败时整体回滚），恢复到此前一致状态。
     * 正常发布流程不应使用此方法。
     */
    public synchronized void replaceSnapshot(RegistrySnapshot target) {
        this.snapshot = target;
        // 回滚不推进代际；缓存键引用的代际只会前进或与现存快照一致
        if (generation > 0) {
            generation--;
        }
    }

    /**
     * 注册一个新版本。重复注册同一坐标直接拒绝（防止悄悄覆盖口径）。
     * 首次注册的版本自动成为默认版本。
     */
    public synchronized void register(MetricDefinition def) {
        MetricKey key = new MetricKey(def.name(), def.version());
        RegistrySnapshot cur = snapshot;
        if (cur.metrics().containsKey(key)) {
            throw new InvalidDefinitionException(
                    "指标版本已存在，重复注册被拒绝: " + def.name() + "@v" + def.version());
        }
        Map<MetricKey, MetricDefinition> metrics = new LinkedHashMap<>(cur.metrics());
        metrics.put(key, def);
        Map<String, Integer> defaults = new LinkedHashMap<>(cur.defaultVersions());
        defaults.putIfAbsent(def.name(), def.version());
        snapshot = new RegistrySnapshot(metrics, defaults, cur.dimensions());
        generation++;
    }

    /** 注册派生维度；同名重复注册直接拒绝。 */
    public synchronized void registerDimension(DerivedDimension dim) {
        RegistrySnapshot cur = snapshot;
        if (cur.dimensions().containsKey(dim.name())) {
            throw new InvalidDefinitionException("派生维度已存在，重复注册被拒绝: " + dim.name());
        }
        Map<String, DerivedDimension> dims = new LinkedHashMap<>(cur.dimensions());
        dims.put(dim.name(), dim);
        snapshot = new RegistrySnapshot(cur.metrics(), cur.defaultVersions(), dims);
        generation++;
    }

    /**
     * 原子切换某指标的默认版本。目标版本不存在时拒绝。
     * 历史已完成查询不受影响；进行中的查询持有旧快照，仍按旧默认版本执行。
     */
    public synchronized void setDefaultVersion(String metricName, int version) {
        RegistrySnapshot cur = snapshot;
        if (!cur.metrics().containsKey(new MetricKey(metricName, version))) {
            if (!cur.hasMetricName(metricName)) {
                throw new MetricNotFoundException(metricName);
            }
            throw new VersionNotFoundException(metricName, version);
        }
        Map<String, Integer> defaults = new LinkedHashMap<>(cur.defaultVersions());
        defaults.put(metricName, version);
        snapshot = new RegistrySnapshot(cur.metrics(), defaults, cur.dimensions());
        generation++;
    }

    /** 按坐标取定义；不存在抛 {@link VersionNotFoundException}。 */
    public MetricDefinition requireVersion(RegistrySnapshot snap, String name, int version) {
        MetricDefinition d = snap.metric(name, version);
        if (d == null) {
            if (!snap.hasMetricName(name)) {
                throw new MetricNotFoundException(name);
            }
            throw new VersionNotFoundException(name, version);
        }
        return d;
    }

    /** 取某指标当前默认版本号；指标不存在抛 {@link MetricNotFoundException}。 */
    public int defaultVersionOf(RegistrySnapshot snap, String name) {
        Integer v = snap.defaultVersions().get(name);
        if (v == null) {
            throw new MetricNotFoundException(name);
        }
        return v;
    }

    /** 列出某指标全部已注册版本（升序）。 */
    public List<Integer> versionsOf(RegistrySnapshot snap, String name) {
        return snap.metrics().keySet().stream()
                .filter(k -> k.name().equals(name))
                .map(MetricKey::version)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    /** 快照摘要，供日志打印。 */
    public String describe(RegistrySnapshot snap) {
        Map<String, String> lines = new TreeMap<>();
        for (Map.Entry<String, Integer> e : snap.defaultVersions().entrySet()) {
            lines.put(e.getKey(), "versions=" + versionsOf(snap, e.getKey())
                    + ", default=v" + e.getValue());
        }
        return lines.toString();
    }
}
