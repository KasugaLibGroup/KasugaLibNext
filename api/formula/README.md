# Formula Module

中文版本：[README.zh-CN.md](README.zh-CN.md)

`modules/formula` is KasugaLib's general-purpose expression engine: arithmetic (`compute`) plus boolean logic (`logic`), with namespace-scoped variable and function registration. It is a **pure JVM library with zero Minecraft dependencies** — it does not touch the game, so it can be consumed by `modelling`, `core`, `scripting`, or any other module.

The engine was migrated from the 1.0 Forge external artifact `Mixed-Arithmetic-Logic-Interpreter` and is the expression layer behind Blockbench `data_points` (e.g. `"time * 360"`, `"math.sin(time * 180) * 30"`, `query.anim_time * 45`) and future FSM conditions.

## Prerequisites

- Java 21 (the module's Gradle toolchain is pinned to Java 21).
- No Minecraft/NeoForge runtime is required. Unit tests run on plain JUnit 5.
- The module is registered through `settings.gradle` (`include ':modules:formula'`). Consumers add `implementation project(":modules:formula")`.

## Quick Start

```java
import lib.kasuga.formula.Code;
import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.formula.compute.infrastructure.Formula;

// A fresh namespace inherits every built-in function and constant from the root.
Namespace ns = new Namespace(Code.ROOT_NAMESPACE);

Formula f = Code.decodeFormula("sin(rad(time * 180)) * 30", ns);
ns.assign("time", 0.5f);          // variables are auto-registered on first use (initial 0)
float result = f.getResult();     // sin(90°) * 30
```

Logical expressions decode the same way:

```java
var cond = Code.decodeLogical("a > 3 and b <= 10", ns);
ns.assign("a", 5f);
ns.assign("b", 9f);
boolean ok = cond.getResult();    // true
```

## Arithmetic Formulas

### Operators

| Operator | Meaning | Precedence |
| --- | --- | --- |
| `^` | power (`Math.pow`) | highest |
| `*` `/` `%` | multiply, divide, modulo — same level, left-associative | middle |
| `+` `-` | add, subtract — same level, left-associative | lowest |

Modulo shares the precedence of `*` and `/`: `10 % 3 * 5` evaluates as `(10 % 3) * 5 = 5`, not `10 % 15`.

`**` is accepted as an alias for `^` (Python style): `2 ** 3 == 8`.

Unary minus is supported in all forms: `-5`, `-(2 + 3)`, `2 * -3`, `3 - -2`. Parentheses nest arbitrarily deep.

### Built-in Functions

Registered on the root namespace by `Code`:

| Arity | Functions |
| --- | --- |
| 1 | `cos sin tan asin acos atan log lg exp round sqrt rad deg floor ceil` |
| 2 | `pow max min` |
| 3 | none by default — register with `namespace.register3Param(...)` |

Trigonometric functions take radians. `rad`/`deg` convert between degrees and radians. Arguments are separated by commas: `pow(2, 10)`, `max(3, 7)`.

### Static Constants

`pi` and `e` are registered as read-only static variables (they live in `STATIC_VARS`, so `assign` on them is a no-op).

### Variables

- Names start with a lowercase letter: `[a-z][a-z1-9]*`, optionally containing `_`.
- **Dotted names** namespace variables: `query.anim_time` is a single variable whose codec contains a `.`.
- A variable used in an expression is **auto-registered** on the instance map with initial value `0`; `namespace.assign("x", v)` re-reads on every `getResult()` call.

## Logical Formulas

### Comparison Operators

`>`, `<`, `>=`, `<=`, `==`, `!=`, and `<>` (alias for `!=`). Symbol operators do **not** require surrounding spaces: `a>b`, `a>=5`, and `a+1==6` all parse correctly.

### Boolean Operators

`and`, `or`, `not` (word operators). Because they are words, they **require** surrounding spaces so they cannot collide with variable names: `nota` is a variable, while `not a` is a negation. Literal booleans are spelled `True` / `False` (capitalized). Numeric values are truthy when non-zero.

### Precedence

Highest to lowest: `==`, `!=`, `> <`, `>= <=`, `not`, `and`, `or`. Use parentheses to override: `not (a > 3)`.

### Limits

- **Chained comparisons are not supported**: `a > b > c` throws `FormulaSyntaxError` (the intermediate result is a boolean, not a numeric). Write `a > b and b > c` instead.
- Mixed relational operators in one expression (e.g. `a == b > c`) are not supported for the same reason.

## Namespaces And Registration

`Namespace` is the symbol table. A child namespace copies the parent's functions, static variables, and instance variables at construction; later registrations stay local.

| API | Behavior |
| --- | --- |
| `register(codec, function)` | register a function instance |
| `register1Param/2Param/3Param(codec, computer)` | register a function from a lambda |
| `register(codec, value)` | register a static (read-only) variable |
| `registerInstance(codec, assignable)` | register a runtime variable (auto-created when an expression uses an unknown name) |
| `assign(codec, value)` | write a runtime variable |
| `containsInstance(codec)` / `getInstance(codec)` | inspect the instance map |
| `clone()` | shallow copy — the maps are copied, the `parent` link is preserved |

`Code` is the static facade over the root namespace: `Code.decodeFormula`, `Code.encodeFormula`, `Code.decodeLogical`, `Code.encodeLogical`, `Code.root()`.

## Error Handling

All exceptions extend `RuntimeException`:

| Exception | Meaning |
| --- | --- |
| `FormulaSyntaxError` | malformed expression (unbalanced brackets, bad operator sequence, non-numeric operand in a comparison, ...). Carries an optional message and a `(formula, position)` hint. |
| `FormulaParseError` | a token could not be parsed into any formula element; wraps the underlying `FormulaSyntaxError` plus the offending input string. |
| `FormulaOperationError` | an operator received an operator as an operand (e.g. two operators in a row). |

## Development And Debugging

Run from the repository root:

```powershell
.\gradlew.bat :modules:formula:test
.\gradlew.bat :modules:formula:compileJava
```

To run one test class:

```powershell
.\gradlew.bat :modules:formula:test --tests lib.kasuga.formula.FormulaComputeTest
```

The unit tests cover operator precedence (including `%` sharing `* /` precedence), `**` power, unary minus, function nesting, variable assignment/reassignment, dotted names, no-space symbol operators, word-operator spacing, and the chained-comparison error.

## Relevant Source Locations

| Area | Source |
| --- | --- |
| Static facade and built-in functions | `modules/formula/src/main/java/lib/kasuga/formula/Code.java` |
| Symbol table | `modules/formula/src/main/java/lib/kasuga/formula/compute/data/Namespace.java` |
| Arithmetic line parser / evaluator | `modules/formula/src/main/java/lib/kasuga/formula/compute/data/Line.java` |
| Numeric / operator / variable atoms | `modules/formula/src/main/java/lib/kasuga/formula/compute/data/{Numeric,Operational,Variable}.java` |
| Function family | `modules/formula/src/main/java/lib/kasuga/formula/compute/data/functions/` |
| Logic line parser / evaluator | `modules/formula/src/main/java/lib/kasuga/formula/logic/data/LogicalLine.java` |
| Logic atoms and operators | `modules/formula/src/main/java/lib/kasuga/formula/logic/{data,operations}/` |
| Exceptions | `modules/formula/src/main/java/lib/kasuga/formula/compute/exceptions/` |
| Unit tests | `modules/formula/src/test/java/lib/kasuga/formula/` |
