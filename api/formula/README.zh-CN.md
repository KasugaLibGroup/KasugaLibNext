# Formula 模块

English version: [README.md](README.md)

`modules/formula` 是 KasugaLib 的通用表达式引擎：`compute`（算术）+ `logic`（布尔逻辑），带命名空间作用域的变量与函数注册。它是**零 Minecraft 依赖的纯 JVM 库**——不触碰游戏本体，可供 `modelling`、`core`、`scripting` 等任意模块复用。

引擎从 1.0 Forge 版外部依赖 `Mixed-Arithmetic-Logic-Interpreter` 迁移而来，是 Blockbench `data_points` 表达式层（如 `"time * 360"`、`"math.sin(time * 180) * 30"`、`query.anim_time * 45`）以及未来 FSM 条件的基础设施。

## 前置要求

- Java 21（模块 Gradle toolchain 固定为 21）。
- 无需 Minecraft/NeoForge 运行时，单元测试跑在纯 JUnit 5 上。
- 模块通过 `settings.gradle`（`include ':modules:formula'`）注册；消费方加 `implementation project(":modules:formula")`。

## 快速开始

```java
import lib.kasuga.formula.Code;
import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.formula.compute.infrastructure.Formula;

// 新命名空间继承根命名空间的所有内置函数与常量。
Namespace ns = new Namespace(Code.ROOT_NAMESPACE);

Formula f = Code.decodeFormula("sin(rad(time * 180)) * 30", ns);
ns.assign("time", 0.5f);          // 变量首次使用自动注册（初值 0）
float result = f.getResult();     // sin(90°) * 30
```

逻辑表达式同样解码：

```java
var cond = Code.decodeLogical("a > 3 and b <= 10", ns);
ns.assign("a", 5f);
ns.assign("b", 9f);
boolean ok = cond.getResult();    // true
```

## 算术公式

### 运算符

| 运算符 | 含义 | 优先级 |
| --- | --- | --- |
| `^` | 幂（`Math.pow`） | 最高 |
| `*` `/` `%` | 乘、除、取模——同级，左结合 | 中 |
| `+` `-` | 加、减——同级，左结合 | 最低 |

**`%` 与 `*` `/` 同级**：`10 % 3 * 5` 按 `(10 % 3) * 5 = 5` 计算，而不是 `10 % 15`。

`**` 是 `^` 的别名（Python 风格）：`2 ** 3 == 8`。

一元负号全形式支持：`-5`、`-(2 + 3)`、`2 * -3`、`3 - -2`。括号可任意嵌套。

### 内置函数

由 `Code` 注册在根命名空间：

| 参数个数 | 函数 |
| --- | --- |
| 1 | `cos sin tan asin acos atan log lg exp round sqrt rad deg floor ceil` |
| 2 | `pow max min` |
| 3 | 默认无——用 `namespace.register3Param(...)` 注册 |

三角函数按**弧度**计算；`rad`/`deg` 负责角度与弧度互转。参数用逗号分隔：`pow(2, 10)`、`max(3, 7)`。

### 静态常量

`pi` 与 `e` 注册为只读静态变量（位于 `STATIC_VARS`，对其 `assign` 是空操作）。

### 变量

- 名称以小写字母开头：`[a-z][a-z1-9]*`，可含 `_`。
- **点号命名**做变量作用域分隔：`query.anim_time` 是 codec 含 `.` 的单一变量。
- 表达式中用到的变量**自动注册**到实例表（初值 0）；每次 `getResult()` 都会读 `namespace.assign("x", v)` 写入的最新值。

## 逻辑公式

### 比较运算符

`>`、`<`、`>=`、`<=`、`==`、`!=`，以及 `<>`（`!=` 的别名）。**符号型运算符不要求两侧空格**：`a>b`、`a>=5`、`a+1==6` 都能正确解析。

### 布尔运算符

`and`、`or`、`not`（单词型运算符）。因为是单词，**必须**有两侧空格才能与变量名区分：`nota` 是变量，`not a` 是否定。布尔字面量写作 `True` / `False`（首字母大写）。数值非零即真（truthy）。

### 优先级

从高到低：`==`、`!=`、`> <`、`>= <=`、`not`、`and`、`or`。用括号覆盖：`not (a > 3)`。

### 限制

- **不支持链式比较**：`a > b > c` 抛 `FormulaSyntaxError`（中间结果是真值而非数值）。请写成 `a > b and b > c`。
- 同理，一个表达式内混用多个关系运算符（如 `a == b > c`）不受支持。

## 命名空间与注册

`Namespace` 是符号表。子命名空间在构造时**拷贝**父级的函数、静态变量与实例变量；之后的注册只影响自身。

| API | 行为 |
| --- | --- |
| `register(codec, function)` | 注册函数实例 |
| `register1Param/2Param/3Param(codec, computer)` | 用 lambda 注册函数 |
| `register(codec, value)` | 注册静态（只读）变量 |
| `registerInstance(codec, assignable)` | 注册运行时变量（表达式用了未知名时自动创建） |
| `assign(codec, value)` | 写运行时变量 |
| `containsInstance(codec)` / `getInstance(codec)` | 查询实例表 |
| `clone()` | 浅拷贝——map 被复制，`parent` 链接保留 |

`Code` 是根命名空间的静态门面：`Code.decodeFormula`、`Code.encodeFormula`、`Code.decodeLogical`、`Code.encodeLogical`、`Code.root()`。

## 错误处理

所有异常都继承 `RuntimeException`：

| 异常 | 含义 |
| --- | --- |
| `FormulaSyntaxError` | 表达式畸形（括号不配对、运算符序列非法、比较中出现非数值操作数等）。携带可选 message 与 `(formula, position)` 提示。 |
| `FormulaParseError` | 某个 token 无法解析成任何公式元素；包装底层 `FormulaSyntaxError` 与出错输入串。 |
| `FormulaOperationError` | 运算符把运算符当成了操作数（如连续两个运算符）。 |

## 开发与调试

在仓库根目录运行：

```powershell
.\gradlew.bat :modules:formula:test
.\gradlew.bat :modules:formula:compileJava
```

只跑一个测试类：

```powershell
.\gradlew.bat :modules:formula:test --tests lib.kasuga.formula.FormulaComputeTest
```

单元测试覆盖：运算符优先级（含 `%` 与 `* /` 同级）、`**` 幂、一元负号、函数嵌套、变量赋值/重赋值、点号命名、无空格符号运算符、单词运算符空格要求、链式比较报错。

## 相关源码位置

| 区域 | 源码 |
| --- | --- |
| 静态门面与内置函数 | `modules/formula/src/main/java/lib/kasuga/formula/Code.java` |
| 符号表 | `modules/formula/src/main/java/lib/kasuga/formula/compute/data/Namespace.java` |
| 算术行解析/求值 | `modules/formula/src/main/java/lib/kasuga/formula/compute/data/Line.java` |
| 数字/运算符/变量原子 | `modules/formula/src/main/java/lib/kasuga/formula/compute/data/{Numeric,Operational,Variable}.java` |
| 函数族 | `modules/formula/src/main/java/lib/kasuga/formula/compute/data/functions/` |
| 逻辑行解析/求值 | `modules/formula/src/main/java/lib/kasuga/formula/logic/data/LogicalLine.java` |
| 逻辑原子与运算符 | `modules/formula/src/main/java/lib/kasuga/formula/logic/{data,operations}/` |
| 异常 | `modules/formula/src/main/java/lib/kasuga/formula/compute/exceptions/` |
| 单元测试 | `modules/formula/src/test/java/lib/kasuga/formula/` |
