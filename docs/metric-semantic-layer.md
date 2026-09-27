# 本地指标定义与查询改写引擎

一套运行在单机内存数据上的指标语义层：用**本地声明**定义指标与派生维度，查询时按维度
分组、筛选、排序；支持**指标口径版本化**、**复合指标固定版本依赖**，并在并发与重复查询下
给出稳定、可解释、可复现的结果。所有畸形定义都会被**分类拒绝**，绝不静默按空值或零处理。

---

## 1. 总体结构

```
声明式定义 (MetricDefinition / DerivedDimension)
        │  先校验（未知字段 / 类型 / 自引用 / 结构）
        ▼
MetricRegistry（不可变快照 + copy-on-write 原子发布，代际 generation）
        │
QueryRequest ──► SemanticEngine（抓快照对 + 查缓存）
                        │
                        ▼
                  QueryPlanner（版本解析 → 维度投影 → 过滤分组
                                   → 递归固定版本求值 → having/排序/limit）
                        │
                  LocalTable / DataSnapshot（不可变内存数据）
```

核心包：

| 包 | 职责 |
|---|---|
| `model` | `MetricDefinition`、`DerivedDimension`、`QueryRequest/Result`、`DataSnapshot` 等不可变模型 |
| `expr` | 表达式词法/语法解析器（`ExpressionParser`）与 AST（`Expr`） |
| `error` | 8 种**彼此可区分**的定义异常 |
| `registry` | copy-on-write 的版本化注册表 |
| `engine` | 校验器、求值器、聚合器、查询执行器、缓存、门面 |
| `catalog` | 本地演示数据与启动装配 |
| `api` | HTTP 入口与异常到状态码的映射 |

---

## 2. 指标定义的写法

一个指标版本 `MetricDefinition` 包含：

| 字段 | 含义 | 示例 |
|---|---|---|
| `name` | 指标名（同名可有多个版本） | `gmv` |
| `version` | 版本号，从 1 开始 | `2` |
| `table` | 来源本地表 | `orders` |
| `sourceColumn` | 聚合源列（`COUNT` 或带行级派生表达式时可省略） | `amount` |
| `agg` | 聚合：`SUM/AVG/COUNT/COUNT_DISTINCT/MIN/MAX` | `SUM` |
| `filterExpression` | 聚合前**行过滤**，必须为布尔表达式 | `is_refund = false` |
| `expression` | 派生表达式：行级派生（引用列）**或**复合指标（引用指标），二者不可混用 | `price * qty`、`gmv@2 / orders@1` |
| `description` | 口径说明 | `剔除退款` |

派生维度 `DerivedDimension`：`(name, table, expression, resultType)`，只能引用本表列，
**不能引用指标**，且声明的 `resultType` 必须与表达式推断类型一致。

### 2.1 表达式语法（子集）

```
or   := and ( 'or' and )*
and  := cmp ( 'and' cmp )*
cmp  := add (('='|'!='|'<'|'<='|'>'|'>=') add)?
add  := mul (('+'|'-') mul)*
mul  := unary (('*'|'/') unary)*
unary:= ('-'|'!') unary | primary
primary := 数字 | 字符串 | true/false
         | 列名 | 指标名@版本号
         | 函数名(参数...) | '(' 表达式 ')'
```

内置函数：

- 空值/分支：`coalesce(a,b,...)`、`if(cond,a,b)`、`case_when(c1,v1,c2,v2,...,默认值?)`
- 数值：`abs(x)`、`neg(x)`、`round(x[, digits])`
- 字符串：`length(s)`（别名 `str_len`）、`concat(...)`
- 日期：演示数据中日期以 `yyyyMMdd` 整数承载，`year/month/day(列)` 取年/月/日

关键字 `and/or/true/false` 大小写不敏感；标识符支持中文。字符串用单引号或双引号。

### 2.2 两类指标表达式

1. **行级派生（聚合前计算）**：只引用源表列，结果必须是数值。
   不匹配的行可通过无默认值的 `case_when` 得到 `null`，聚合时被跳过（注意是 `null` 而不是 `0`，
   否则会污染 `AVG`）：

   ```java
   new MetricDefinition("gmv_q1only", 1, "orders", null, AggType.SUM,
           null, "case_when(year(biz_date) = 2024 and biz_date <= 20240331, amount)",
           "仅统计 2024 一季度成交金额");
   ```

2. **复合指标（指标依赖指标）**：表达式中以 `指标名@版本号` 引用**固定版本**的其它指标，
   此时 `agg` 必须为 `COUNT` 且不指定源列：

   ```java
   new MetricDefinition("aov", 1, "orders", null, AggType.COUNT,
           null, "gmv@2 / orders@1", "客单价 = gmv v2 / orders v1");
   ```

### 2.3 空值与除零

- 算术 / 比较遇 `null` 传播为 `null`；过滤条件在布尔上下文中把 `null` 视为不通过；
- 整数除零抛 `InvalidDefinitionException`，浮点除零按 IEEE-754 得 `NaN/Infinity`；
- 空组：`COUNT/COUNT_DISTINCT` 返回 `0`，其余聚合返回 `null`（不伪造 0）。

---

## 3. 失败原因如何被分类拒绝

任何畸形定义都不会静默通过，调用方拿到的是**类型可区分**的异常（`error` 包）：

| 异常 | 触发场景 | HTTP |
|---|---|---|
| `MalformedExpressionException` | 括号不匹配、token 残缺、`@` 后非正整数版本号等语法错误 | 400 |
| `UnknownFieldException` | 源列/过滤/分组/having 引用了不存在的列或未注册维度 | 400 |
| `TypeMismatchException` | SUM 字符串、字符串参与乘法、过滤条件非布尔、派生维度声明类型不符、两侧类型不可比 | 422 |
| `SelfReferenceException` | 指标表达式直接引用同名同版本自身 | 409 |
| `CircularDependencyException` | 指标依赖成环（异常携带环路路径） | 409 |
| `MetricNotFoundException` | 查询/依赖了根本不存在的指标名 | 404 |
| `VersionNotFoundException` | 指标存在但请求/依赖的版本号不存在 | 404 |
| `InvalidDefinitionException` | 重复注册同版本、SUM 无源列也无派生表达式、维度引用指标、维度与物理列重名、表不存在、未知函数等 | 400 |

### 依赖校验时机（支持前向引用）

- **注册单个指标**：只做自包含校验（字段、类型、自引用、结构），允许引用一个"稍后注册"的指标；
- **查询时**：沿固定版本引用惰性解析依赖闭包，缺失版本抛 `VersionNotFoundException`，
  环抛 `CircularDependencyException`；
- **显式全量校验**：`SemanticEngine.validateAll()` 校验整张依赖图。
- 畸形定义注册失败时，注册表**代际不推进、不留半成品**，既有指标继续可查。

### 依赖可追溯

查询结果 `QueryResult.resolvedChain` 按求值顺序列出触达的全部指标版本，例如
`[gmv@v2, orders@v1, aov@v1]`；`rationale` 说明版本选择依据（显式指定还是默认）、
数据快照、过滤条数、排序/having 等判定依据。

---

## 4. 版本兼容规则

1. **不可变版本**：`(name, version)` 一经注册不可覆盖；重复注册抛
   `InvalidDefinitionException`。口径要改只能发新版本。
2. **默认版本**：首次注册的版本自动成为默认版本；`setDefaultVersion(name, version)` 原子切换。
3. **历史可复现**：查询可显式传 `metricVersion`，永远命中该版本；不传则取查询开始时刻的默认版本。
   默认版本更新后，显式旧版本查询结果**不变**，默认查询跟随新版本。
4. **依赖固定版本，绝不跨版本混用**：复合指标写死 `gmv@2`，即使 `gmv` 的默认版本切到 v1/v3，
   该复合指标仍按 v2 求值。系统不会在一次查询里把不同版本的同一指标口径混在一起。
5. 切换默认版本只影响**之后开始**的查询；进行中的查询持有旧注册表快照，不受影响。

---

## 5. 并发一致性保证

### 5.1 不可变快照 + 原子发布

- `MetricRegistry` 内部只有一个 `volatile RegistrySnapshot`，所有字段（指标、默认版本、维度）
  都是不可变 `Map`。注册 / 切换默认版本在锁内 copy-on-write 重建整张快照后**一次性换引用**。
- 查询开始时抓取一对 `(RegistrySnapshot, DataSnapshot)` 并全程持有。因此：
  - 更新口径过程中，进行中的查询读不到"只改了一半"的定义；
  - 并发切换默认版本时，一次查询要么整体命中旧默认版本，要么整体命中新版本，不会出现混合结果；
  - 数据快照 `DataSnapshot` 本身也是不可变的，换数据 = 换快照对象 + 换 id。

### 5.2 同快照结果一致

查询是纯函数：相同注册表代际 + 相同数据快照 + 相同请求 ⇒ 相同结果。并发测试中
16 线程 × 50 次分组复合指标查询，结果逐行相等。

### 5.3 缓存：不重复求值，也不返回过期结果

`QueryCache` 的键包含：数据快照 id、注册表代际 `generation`、指标名、显式版本、
分组维度、全部过滤、having、排序、limit。

- 口径一变，代际 +1；数据一变，快照 id 变——缓存键随之改变，旧结果**不可能**被新口径/新数据命中，
  因此不会返回过期结果；
- 同一快照 + 同一代际下的重复查询直接命中内存缓存。实测 20 万行分组聚合冷查询约 282ms，
  100 次重复热查询合计约 1ms（数值随机器波动，量级可复现）。
- 单次查询内的递归指标求值另有按 `(指标版本, 组 id, 过滤上下文)` 的记忆化，
  复合指标的共享依赖不会重复计算。

---

## 6. 查询写法

`QueryRequest`（JSON 字段同名）：

```json
{
  "metricName": "aov",
  "metricVersion": null,
  "table": "orders",
  "dimensions": ["region", "market"],
  "filters": ["is_refund = false", "channel = 'app'"],
  "having": "aov >= 100",
  "orderBy": "aov",
  "sortOrder": "DESC",
  "limit": 10,
  "snapshotId": null
}
```

- `metricVersion` 省略即默认版本；`dimensions` 可混合物理列与派生维度；
- `filters` 为聚合前过滤（可引用物理列与派生维度，不可引用指标）；
- `having` 为聚合后过滤，可引用指标本身与分组维度；
- `orderBy` 支持指标名或维度，`null` 值排序最靠前（DESC 时最靠后）。

---

## 7. 本地验证方法

### 7.1 运行全部测试

```bash
mvn clean test
```

测试分四类（共 20+ 用例），日志中打印了**输入、命中版本、判定依据**：

| 测试类 | 覆盖内容 |
|---|---|
| `QueryHappyPathTest` | 双版本指标、物理/派生维度分组、过滤/having/排序/limit、复合指标依赖链、行级派生、缓存重复查询 |
| `MalformedDefinitionTest` | 8 类异常的分类拒绝、注册失败一致性、查询触达缺失依赖/环 |
| `VersioningTest` | 切换默认版本后历史结果复现、复合指标不跨版本漂移、并发切换只出现合法口径值 |
| `ConcurrencyConsistencyTest` | 同快照并发一致、20 万行缓存性能、口径/数据变化后不过期、并发注册无半成品 |
| `MetricApiTest` | HTTP 查询、注册表查看、错误码映射、默认版本切换 |

查看带输入/版本/依据的日志：

```bash
mvn test -Dtest=VersioningTest
# 或在 target/surefire-reports/TEST-*.xml 中查看 SystemOut 日志
```

### 7.2 启动服务手动调用

```bash
mvn spring-boot:run
```

演示数据（10 行订单，含 CN/US、app/web、退款标记、2023/2024 日期）和指标
`gmv@1(含退款)/gmv@2(剔除退款)/orders@1/aov@1(=gmv@2/orders@1)/gmv_q1only@1`，
派生维度 `market/order_year` 在启动时自动装载。

```bash
# 查看注册表（版本、默认版本、代际、快照 id）
curl -s localhost:8080/api/metrics | jq .

# 查询 gmv v2，按区域分组，倒序取 1 行
curl -s -X POST localhost:8080/api/metrics/query \
  -H 'Content-Type: application/json' \
  -d '{"metricName":"gmv","metricVersion":2,"dimensions":["region"],"orderBy":"gmv","sortOrder":"DESC","limit":1}' | jq .

# 切换默认版本
curl -s -X POST 'localhost:8080/api/metrics/gmv/default?version=2' | jq .

# 错误码可区分（404 + MetricNotFoundException）
curl -i -X POST localhost:8080/api/metrics/query \
  -H 'Content-Type: application/json' -d '{"metricName":"ghost"}'
```

### 7.3 演示数据口径速查

- `gmv v1`（含退款）= 1800；`gmv v2`（剔除 id 3、6 两笔退款）= 1300；
- `orders v1`（非退款订单数）= 8；`aov v1 = 1300/8 = 162.5`；
- CN gmv=880，US gmv=920；2023 gmv=530，2024 gmv=1270；2024Q1 gmv=910。
