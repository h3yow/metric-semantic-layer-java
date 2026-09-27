# metric-semantic-layer-java（bootstrap-b）

本地指标定义与查询改写引擎：内存数据模拟分析表，支持指标口径版本化、派生维度、
复合指标固定版本依赖，以及并发/重复查询下的稳定一致结果。畸形定义分类拒绝、不静默吞错。

## 快速开始

```bash
mvn clean test          # 全部测试（含并发/版本复现/分类拒绝）
mvn spring-boot:run     # 启动后用 /api/metrics 手动验证
```

核心演示口径（启动自动装载）：

- `gmv@1` 成交总额含退款 = 1800；`gmv@2` 剔除退款 = 1300；
- `orders@1` 非退款订单数 = 8；`aov@1 = gmv@2 / orders@1 = 162.5`（依赖锁版本，不跨版本漂移）；
- 派生维度：`market`（境内/境外）、`order_year`。

## 文档

详见 [docs/metric-semantic-layer.md](docs/metric-semantic-layer.md)：

- 指标 / 派生维度定义写法与表达式语法
- 8 类可区分的定义错误与拒绝时机
- 版本兼容规则（历史复现、默认版本、固定版本依赖）
- 并发一致性（不可变快照 + 原子发布）与缓存（不重复求值、不过期）
- 本地验证方法与 HTTP 示例

## 代码地图

| 包 | 职责 |
|---|---|
| `model` | 指标/维度/查询/快照不可变模型 |
| `expr` | 表达式解析器与 AST |
| `error` | 可区分的定义异常体系 |
| `registry` | copy-on-write 版本化注册表 |
| `engine` | 校验、求值、聚合、查询改写、缓存、门面 |
| `catalog` | 本地演示数据与装配 |
| `api` | REST 入口与错误码映射 |
