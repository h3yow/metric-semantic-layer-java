# Metric Semantic Layer (Java Bootstrap)

一套**本地**的指标定义与查询改写引擎：用内存表模拟分析事实表，让不同口径、不同版本的查询都能得到
**稳定、可复现、可解释**的结果。无数据库、无外部依赖，仅需 JDK 21+ 与 Maven。

---

## 1. 能力总览

| 能力 | 说明 |
| --- | --- |
| 本地声明式指标 | 基础指标（聚合 + 过滤 + 度量表达式）、派生指标（指标公式）、派生维度（列公式） |
| 查询改写 | 按物理列 / 派生维度 `GROUP BY`，聚合后 `HAVING` 过滤、`ORDER BY`、`LIMIT` |
| 严格校验 | 未知字段、类型不匹配、自引用、循环依赖、未知指标/版本——**拒绝并给出可区分的错误码**，绝不静默按 null/0 处理 |
| 依赖追溯 | 每次结果附带完整血缘（指标名 → 实际命中版本 → 表/聚合/过滤 → 上游依赖版本） |
| 口径版本化 | 同名指标多版本并存；默认版本可切换；历史版本语义可显式复现；派生指标对依赖**显式钉版本**，杜绝跨版本混用 |
| 并发一致性 | 快照与目录均不可变、原子发布；查询开始即钉住单一“代次”；同快照并发/重复查询结果一致 |
| 性能 | 基础指标聚合与派生维度物化按“代次”记忆化，重复/并发查询不重复全表扫描；代次切换缓存随旧引擎一起失效，不返回过期值 |

---

## 2. 指标定义的写法

定义通过 Java DSL 本地声明（位置：`src/main/java/.../local/LocalDemoCatalog.java`）。
表达式 DSL 在 `expr/Expressions.java`：

```java
import static ...expr.Expressions.*;

col("amount")                       // 列引用
lit(100L), lit("CN"), lit(false)    // 类型化字面量: LONG/STRING/BOOLEAN (另有 double)
plus / minus / times / div          // 数值算术: LONG op LONG = LONG, 否则提升为 DOUBLE
eq / ne / gt / ge / lt / le         // 比较: 两侧类型必须一致, 结果 BOOLEAN
and / or / not                      // 布尔运算
cond(when, then, otherwise)         // CASE WHEN: 条件 BOOLEAN, 两个分支类型必须一致
isNotNull(expr)                     // 非空判断
// 链式语法糖:
col("channel").eq(lit("web")).or(col("channel").eq(lit("app")))
col("price").times(col("qty"))
```

### 2.1 基础指标 BaseMetricDef

```java
new MetricDef.BaseMetricDef(
    "revenue",                       // 指标名
    2,                               // 版本号(正整数)
    "GMV excl. refunds",             // 人类可读说明
    "orders",                        // 来源物理表
    Aggregation.SUM,                 // SUM/COUNT/COUNT_DISTINCT/AVG/MIN/MAX
    col("amount"),                   // 度量表达式; COUNT 行数时传 null
    col("is_refunded").eq(lit(false))) // 行级过滤(可空); 必须是 BOOLEAN
```

支持的聚合与结果类型：

| 聚合 | 输入要求 | 结果类型 |
| --- | --- | --- |
| `SUM` | 数值 | 与输入一致（LONG→LONG，DOUBLE→DOUBLE） |
| `COUNT` | 无需度量（计行） | LONG |
| `COUNT_DISTINCT` | 任意 | LONG（null 不计） |
| `AVG` | 数值 | DOUBLE（无匹配行时为 null） |
| `MIN` / `MAX` | 任意可比较类型 | 与输入一致 |

### 2.2 派生维度 DerivedDimensionDef

挂在物理表上的派生列，可引用物理列和**同表先声明**的派生维度：

```java
new DerivedDimensionDef("orders", "channel_group",
    cond(col("channel").eq(lit("web")).or(col("channel").eq(lit("app"))),
         lit("online"), lit("offline")));
```

### 2.3 派生指标 DerivedMetricDef

指标公式，**每个依赖都必须显式带版本号**：

```java
new MetricDef.DerivedMetricDef(
    "gross_profit", 2, "Revenue v2 - cost",
    minus(col("revenue"), col("cost")),                       // 公式
    List.of(new MetricRef("revenue", 2),                      // 显式钉住 revenue v2
            new MetricRef("cost", 1)));                       // 显式钉住 cost v1
```

### 2.4 构建即校验（Catalog.builder）

```java
Catalog catalog = Catalog.builder(revision)
    .withTables(snapshot.tables())
    .addMetric(...)
    .addDimension(...)
    .defaultVersion("revenue", 2)   // 可选: 指定默认版本(默认取最大版本号)
    .build();                        // 全量校验失败则整包拒绝, 不会发布半成品
```

构建时会依次完成：派生维度按声明序类型绑定 → 基础指标度量/过滤绑定 →
依赖引用解析（未知指标/版本、自引用、重复依赖）→ 版本化依赖图环检测 →
派生公式按拓扑序类型检查。

错误通过异常类型（`.code()`）彼此区分：

| code | 触发场景 |
| --- | --- |
| `UnknownFieldException` | 定义/查询引用了不存在的列或派生字段 |
| `TypeMismatchException` | 聚合/算术/比较/条件/过滤的类型不兼容 |
| `SelfReferenceException` | 指标或维度直接引用自身 |
| `CircularDependencyException` | 指标依赖成环（消息中给出环路径） |
| `UnknownMetricException` | 引用了目录中不存在的指标 |
| `UnknownVersionException` | 请求/引用了某指标不存在的版本 |
| `InvalidDefinitionException` | 结构性错误：缺表、SUM 无度量、重复定义、维度重名等 |
| `QueryValidationException` | 查询非法：重复请求同名指标、ORDER BY 列不在输出中等 |

---

## 3. 版本兼容规则

1. **版本不可变**：`(指标名, 版本号)` 一旦发布，其度量、过滤、公式与依赖版本集合全部冻结。
2. **多版本并存**：同一指标可同时存在 v1/v2/…；不显式指定版本时解析到**默认版本**
   （构建时最大版本号，或由 `defaultVersion(...)` 显式指定）。
3. **历史可复现**：默认版本从 v1 切到 v2 后，显式 `metric("revenue", 1)` 的查询
   仍按 v1 口径计算，结果不随默认版本变化；分组查询同样逐行可复现。
4. **依赖必须显式钉版本**：派生指标通过 `MetricRef(name, version)` 引用依赖。
   更新某依赖的默认版本，不会改变任何已定义派生指标的口径——例如
   `gross_profit v1` 永远使用 `revenue[v1]`，`gross_profit v2` 使用 `revenue[v2]`，
   不存在跨版本混用。血缘会把每个实际命中的版本打印出来。
5. **版本化目录整体替换**：目录带单调递增的 `revision`。发布新目录是原子操作：
   要么读到完整的旧目录，要么读到完整的新目录。
6. **未知版本直接拒绝**：请求不存在的版本抛 `UnknownVersionException`。

### 数据快照与“过期结果”

- `DataSnapshot` 不可变并带 `id`；查询可用 `snapshot(id)` 固定读取的快照。
- 默认策略（推荐）下，查询固定的 `snapshotId` 一旦不是当前最新快照，会被拒绝
  （`InvalidDefinitionException: ... refusing to return stale results`），
  避免调用方在不知情时消费过期数据。构造 `new MetricSemanticLayer(true)` 可放宽该策略。
- 每次返回的 `QueryResult` 都带有 `snapshotId`、`catalogRevision`、`versions` 与 `lineage`，
  结果“在什么数据、什么口径下算出来的”一目了然。

---

## 4. 并发一致性保证

实现位于 `engine/MetricSemanticLayer.java` 与 `engine/QueryEngine.java`：

```
AtomicReference<Generation>   Generation = (Catalog, DataSnapshot, QueryEngine)  // 全部不可变
```

- **单代次读取**：查询开始时只读取一次 `Generation` 引用，整个执行过程都落在该代次上，
  不可能看到“只改了一半”的目录，也不可能把两个快照的数据拼在一起。
- **原子发布**：`publishSnapshot` / `publishCatalog` 会用全新的 `QueryEngine`
  装配新的 `Generation` 并整体替换引用；已经在执行中的查询继续使用旧代次直到结束。
- **记忆化与代次绑定**：聚合结果与派生维度物化缓存在 `QueryEngine` 内部
  （`ConcurrentHashMap`），同代次下重复/并发查询不重复扫描；发布新快照或新目录会创建
  全新引擎与空缓存，因此缓存**不可能**把旧快照的值返回给新代次——既快又不会过期。
- **结果稳定**：输出列顺序固定（维度在前、指标在后），分组按首次遇到顺序保留，
  同快照 + 同目录下任意并发次数结果逐行相等。

---

## 5. 查询写法

Java API：

```java
QueryResult r = layer.query(Query.builder()
    .metric("revenue", 2)          // 显式版本; 不传版本则用默认版本
    .metric("gross_profit", 2)
    .groupBy("region")             // 物理列
    .groupBy("channel_group")      // 或派生维度
    .where(gt(col("revenue"), lit(50000.0)))  // 聚合后 HAVING
    .orderBy("revenue", SortOrder.DESC)
    .limit(10)
    .snapshot(1L)                  // 可选: 钉住数据快照
    .build());

r.columns();  r.rows();  r.versions();  r.lineage();  r.snapshotId();  r.catalogRevision();
```

血缘 / 执行计划：

```java
layer.lineage("gross_profit", null);   // LineageNode 树, 每个节点带实际版本
layer.explain(query);                  // 快照、目录版本、版本命中、表扫描、血缘
```

HTTP（应用启动后，端口随机；可用 `-Dserver.port=8080` 固定）：

```bash
curl -s localhost:8080/api/metrics/query -H 'Content-Type: application/json' -d '{
  "metrics":[{"name":"revenue","version":2}],
  "groupBy":["region","channel_group"],
  "orderBy":[{"column":"revenue","direction":"desc"}],
  "limit":5
}'

curl -s localhost:8080/api/metrics/explain -H 'Content-Type: application/json' -d '{
  "metrics":[{"name":"gross_profit"}]
}'

curl -s 'localhost:8080/api/metrics/lineage/gross_profit?version=2'
```

---

## 6. 本地验证方法

```bash
# 全量测试（35 个用例: 定义校验 / 版本复现 / 查询功能 / 并发与性能）
mvn test

# 只跑某一类
mvn test -Dtest=DefinitionValidationTest   # 畸形定义被分类拒绝
mvn test -Dtest=VersioningTest             # 口径切换后历史结果可复现、血缘版本
mvn test -Dtest=QueryEngineTest            # 分组/HAVING/排序/limit/派生维度
mvn test -Dtest=ConcurrencyTest            # 同快照并发一致 + 20万行重复查询性能

# 启动 Web 服务（启动时自动装载 1000 行本地快照与 revision-2 目录）
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
```

测试日志中会打印每个用例的**输入、命中的版本、判定依据**，例如：

```
[版本复现] 输入=revenue | 旧默认(v1)=107032.36 命中版本=1 ; 新默认(v2)=102634.53 命中版本=2 ;
          显式钉v1复现=107032.36 | 判定依据=数值按快照预聚合+版本不可变
[依赖追溯] gross_profit v1=42036.74 lineage->revenue[v1] ; gross_profit v2=37638.91 lineage->revenue[v2]
[并发一致性] 16线程x50次 ... 全部800份结果逐行一致, 命中=snapshot1/revision2
[发布原子性] 50次目录切换期间持续查询 | v1命中=.., v2命中=.., 无撕裂读
[性能] 输入=200000行 revenue GROUP BY region | 首次≈200ms 命中缓存后≈0ms
[定义校验] 输入=SUM(col(region:string)) | 判定依据=code:TypeMismatchException -> ...
```

演示数据由确定性随机种子（`new Random(42)`）生成，因此任何机器上的数值断言都一致。

---

## 7. 代码地图

```
model/        DataType, FieldDef, Row, TableData, DataSnapshot   本地表与不可变快照
expr/         Expr, Scope, Expressions                            类型安全表达式 DSL
definition/   Aggregation, MetricRef, DerivedDimensionDef,
              MetricDef(Base/Derived), Catalog(+Builder)          定义与构建期全量校验
query/        Query, SortOrder, QueryResult, LineageNode          查询模型与血缘
engine/       QueryEngine                                         聚合/派生求值/HAVING/排序 + 代次内记忆化
              MetricSemanticLayer                                 原子发布、代次钉住、过期拒绝
local/        LocalTables(模拟数据), LocalDemoCatalog(多版本口径), DemoSemanticLayerConfig
web/          QueryController                                     /query /explain /lineage
```
