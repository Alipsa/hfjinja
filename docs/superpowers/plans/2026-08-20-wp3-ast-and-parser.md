# WP3 Slice 2 — AST and Parser Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port `ast.ts` and `parser.ts` to `internal.ast` and `internal.parser`, so `Template.parse` turns source into an immutable, span-carrying AST that matches upstream's parser node-for-node.

**Architecture:** `Lexer.tokenize` (already merged) feeds a hand-written recursive-descent `Parser` that mirrors upstream's function-per-precedence-level structure one-to-one. AST nodes are sealed Java interfaces with nested records: `Statement` (statements) and `Expression extends Statement` (expressions), matching upstream's `Expression extends Statement` hierarchy exactly. The parser is verified by a differential AST-snapshot harness that runs the same fixtures through the pinned Node `parse` and through Java, comparing a canonical serialization byte-for-byte — the same technique that validated the lexer across 154,006 cases.

**Tech Stack:** Java 21 (sealed interfaces, records, pattern matching), Gradle (offline), JUnit 5, Node (oracle only, never a runtime dependency).

**Spec:** [`req/project-description.md`](../../../req/project-description.md) — WP3 steps 2–5 of [`req/implementation-plan.md`](../../../req/implementation-plan.md); delivery gate **G3**.

## Global Constraints

Copied from the spec. Every task's requirements implicitly include this section.

- **No production dependencies.** Do not add a dependency to solve parsing, JSON, date formatting, or execution. Test-only and Node-oracle-only tooling is fine.
- **Only `se.alipsa.hfjinja` is exported.** `module-info.java` must not gain an export. `internal.*` types stay internal by package naming and API convention; where cross-package access forces `public`, say so in javadoc without claiming module exports prevent classpath access (see `Token.java:9-11` for the established wording).
- **Port upstream one-to-one.** The correspondence between `parser.ts` functions and Java methods is a deliberate maintenance feature. Keep upstream's function names, order, and structure. Port upstream bugs and quirks; do not "fix" them.
- **Keep `upstream/mapping.yml` current in the same change as every ported behavior.** `upstreamVerify` enforces `status: implemented` entries naming Java and test files.
- **The Java build is offline.** Node runs only in the explicit oracle/corpus tasks.
- **No static mutable state in the parser.** Parsed ASTs are complete immutable values — no lazy memoization, no mutable per-template state.
- **`SourceLocation` is v1 exception data**, plus AST spans. There is no synthetic whole-template `location()`.
- **Java 21 toolchain**, JUnit 5 (`org.junit:junit-bom:5.12.2`), two-space indent, no wildcard imports (match the style of `Lexer.java`).
- **`{@code}` spans must contain balanced braces.** Javadoc terminates the tag at the brace that returns depth to zero, so `{@code {% set x %}}` is fine (it renders `{% set x %}`) but `{@code {%}` is an unterminated tag that swallows the rest of the comment, and `{@code {%}}` renders the wrong literal `{%}`. For a lone delimiter use `<code>&#123;%</code>` / `<code>%&#125;</code>`. Verified with `javadoc -private -Xdoclint:all`; PR #6 shipped this defect twice, so run that command before committing rather than reasoning about the braces.

## Decisions Already Made

Two decisions are settled and are not open for re-litigation during execution:

1. **`TemplateOptions.DEFAULT` flips to `trimBlocks=true, lstripBlocks=true`** (Task 1). Upstream's public `Template` constructor hardcodes `lstrip_blocks: true, trim_blocks: true` (`upstream/vendor/src/index.ts:26-29`). Our default was all-false, which would diverge from the WP4 differential corpus on every whitespace-sensitive template. The user chose to flip the single default rather than carry a second "upstream compat" constant.
2. **`ObjectLiteral` holds a `List<ObjectEntry>`, never a `Map`.** Upstream types it `Map<Expression, Expression>` where the keys are AST node *objects*, so identity — not structure — decides collisions. Verified against the pinned package: `{{ {'a':1,'a':2} }}` parses to an ObjectLiteral of **size 2**. Java records use structural `equals`, so a `LinkedHashMap<Expression, Expression>` would silently collapse those two entries into one. A list of entries preserves both duplicates and insertion order.

## File Structure

**Created:**

| File | Responsibility |
| --- | --- |
| `src/main/java/se/alipsa/hfjinja/internal/ast/Statement.java` | Sealed `Statement` interface; nested records for the eight statement nodes; `permits` list including `Expression`. |
| `src/main/java/se/alipsa/hfjinja/internal/ast/Expression.java` | Sealed `Expression extends Statement`; nested records for the nineteen expression nodes plus the `ObjectEntry` helper record. |
| `src/main/java/se/alipsa/hfjinja/internal/parser/Parser.java` | Recursive-descent parser: bounds-checked cursor, located syntax errors, depth guard, one method per upstream function. |
| `src/test/java/se/alipsa/hfjinja/internal/ast/AstInventoryTest.java` | Asserts the Java node set is exactly the `upstream/ast-allowlist.json` key set. |
| `src/test/java/se/alipsa/hfjinja/internal/parser/ParserTest.java` | Grammar, precedence, error-location, and limit tests. |
| `src/test/java/se/alipsa/hfjinja/internal/parser/AstSnapshot.java` | Test-only canonical AST serializer shared by `ParserTest` and the differential harness. |
| `src/test/java/se/alipsa/hfjinja/internal/parser/AstSnapshotDifferentialTest.java` | Compares Java snapshots against the checked-in Node snapshots. |
| `tools/ast-snapshot/snapshot.mjs` | Emits the same canonical serialization from the pinned Node `parse`. |
| `src/test/resources/ast-snapshots/fixtures.json` | Template sources extracted from upstream `TEST_STRINGS`, each carrying its shared `TEST_STRINGS`/`TEST_PARSED` case name in a `name` field. |
| `src/test/resources/ast-snapshots/upstream-parsed.txt` | Checked-in Node-generated AST snapshots for those sources, under constructor-compatible preprocessing. |

**Modified:**

| File | Change |
| --- | --- |
| `src/main/java/se/alipsa/hfjinja/TemplateOptions.java` | Flip `DEFAULT` trim/lstrip to true; add `maxAstDepth`. |
| `src/main/java/se/alipsa/hfjinja/Template.java` | `parse` tokenizes and parses, retaining an immutable `Statement.Program`. |
| `src/test/java/se/alipsa/hfjinja/PublicApiTest.java:85-105` | Update default assertions; add `maxAstDepth` coverage. |
| `src/test/java/se/alipsa/hfjinja/internal/lexer/LexerTest.java` | Pin shape assertions to an explicit raw-options constant so the `DEFAULT` flip cannot silently change lexer expectations. |
| `upstream/mapping.yml` | `lexer.ts`, `ast.ts`, `parser.ts` → `status: implemented` with Java and test paths. |
| `build.gradle` | `astSnapshotVerify` Node task, wired into `check`. |

**Why two AST files rather than thirty.** A sealed interface whose implementations live in other files needs an explicit `permits` clause listing all of them; nested records in the same compilation unit do not. Two files keep `ast.ts`'s 320 lines mirrored by roughly 400 lines of Java in the same reading order, which is the point of the one-to-one mapping. Callers static-import the nested names (`import static ...ast.Expression.Identifier;`).

## Known Gap This Slice Closes

`lexer.ts` is still `status: planned` with `java: []` in `upstream/mapping.yml` even though `Lexer.java` and `LexerTest.java` merged in PR #6. `upstreamVerify` did not catch it because `planned` only asserts the lists are *empty* — it has no way to know a port exists. Task 8 corrects the entry and adds the verification that would have caught it.

---

### Task 1: Parse-time options — upstream-matching defaults and an AST depth limit

**Files:**
- Modify: `src/main/java/se/alipsa/hfjinja/TemplateOptions.java:6-8`, `:14-20`, `:47-85`
- Modify: `src/test/java/se/alipsa/hfjinja/PublicApiTest.java:85-105`
- Modify: `src/test/java/se/alipsa/hfjinja/internal/lexer/LexerTest.java` (shape-assertion helpers)
- Test: `src/test/java/se/alipsa/hfjinja/PublicApiTest.java`

**Interfaces:**
- Produces: `TemplateOptions.DEFAULT` with `trimBlocks() == true`, `lstripBlocks() == true`; `int TemplateOptions.maxAstDepth()` defaulting to `256`; `TemplateOptions.Builder.maxAstDepth(int)` rejecting non-positive values.

**Why 256.** The depth guard exists to convert deep nesting into a catchable `RESOURCE_LIMIT` failure *before* the JVM throws `StackOverflowError`, which is an `Error` and not an `HfJinjaException`. Each level of expression nesting costs roughly ten frames as the parser descends the full precedence chain (`parseExpression` → `parseIfExpression` → `parseLogicalOrExpression` → … → `parsePrimaryExpression`), so 256 levels is about 2,600 frames — comfortably inside a default JVM stack while still allowing far deeper templates than any real chat template uses.

- [ ] **Step 1: Write the failing test**

In `PublicApiTest.java`, replace the two `assertFalse` lines at `:91-92` and extend the defaults test:

```java
  @Test
  void templateOptionsDefaultsMatchUpstreamTemplateConstructor() {
    assertEquals(1_048_576, TemplateOptions.DEFAULT.maxSourceLength());
    assertEquals(200_000, TemplateOptions.DEFAULT.maxTokenCount());
    assertEquals(256, TemplateOptions.DEFAULT.maxAstDepth());
    // upstream/vendor/src/index.ts:26-29 constructs its Template with both flags on, so the
    // default must match or every whitespace-sensitive corpus case diverges at the WP4 gate.
    assertTrue(TemplateOptions.DEFAULT.trimBlocks());
    assertTrue(TemplateOptions.DEFAULT.lstripBlocks());
  }

  @Test
  void templateOptionsRejectsNonPositiveAstDepth() {
    assertThrows(IllegalArgumentException.class, () -> TemplateOptions.builder().maxAstDepth(0));
    assertThrows(IllegalArgumentException.class, () -> TemplateOptions.builder().maxAstDepth(-1));
  }
```

Add `import static org.junit.jupiter.api.Assertions.assertTrue;` and `assertThrows` if absent; drop the now-unused `assertFalse` import if nothing else uses it.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --offline --tests 'se.alipsa.hfjinja.PublicApiTest'`
Expected: FAIL — `maxAstDepth()` does not compile; the trim/lstrip assertions fail.

- [ ] **Step 3: Implement**

In `TemplateOptions.java`, add the constant and field:

```java
  private static final int DEFAULT_MAX_AST_DEPTH = 256;

  /**
   * Parse-time defaults. {@code trimBlocks} and {@code lstripBlocks} are on because upstream's
   * public {@code Template} constructor hardcodes {@code lstrip_blocks: true, trim_blocks: true};
   * matching it keeps {@link Template#parse(String)} parity-exact with {@code new Template(source)}.
   */
  public static final TemplateOptions DEFAULT =
      builder().trimBlocks(true).lstripBlocks(true).build();
```

Thread `maxAstDepth` through the private constructor, the accessor, the builder field, and `build()`, exactly mirroring `maxTokenCount`:

```java
  /**
   * Returns the maximum AST nesting depth. The parser checks this when it *enters* a recursive
   * production, not when it constructs a node, so deep input fails as {@code RESOURCE_LIMIT}
   * instead of unwinding as an uncatchable {@link StackOverflowError}.
   */
  public int maxAstDepth() {
    return maxAstDepth;
  }
```

```java
    public Builder maxAstDepth(int maxAstDepth) {
      if (maxAstDepth <= 0) {
        throw new IllegalArgumentException("maxAstDepth must be positive");
      }
      this.maxAstDepth = maxAstDepth;
      return this;
    }
```

- [ ] **Step 4: Pin the lexer tests to explicit options**

The `DEFAULT` flip changes what `LexerTest`'s shared helper does. At `LexerTest.java:391`, the helper currently forwards `TemplateOptions.DEFAULT`. Add a test-local constant and use it there, so those assertions keep testing raw scanning:

```java
  /** Raw scanning: the shape assertions below predate the trim/lstrip defaults and assume neither. */
  private static final TemplateOptions RAW = TemplateOptions.builder().build();
```

Replace `TemplateOptions.DEFAULT` with `RAW` at `:391` and at the `:257`, `:369`, `:379`, `:403` call sites. Leave `:282`, `:294`, `:321`, `:334` alone — they already build explicit options.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test --offline`
Expected: PASS, all tests green. If any `LexerTest` case still fails, it is a case whose source is whitespace-sensitive and was relying on the old default — switch that case to `RAW` too rather than changing its expectations.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/se/alipsa/hfjinja/TemplateOptions.java \
  src/test/java/se/alipsa/hfjinja/PublicApiTest.java \
  src/test/java/se/alipsa/hfjinja/internal/lexer/LexerTest.java
git commit -m "Match upstream parse defaults and add an AST depth limit"
```

---

### Task 2: AST node model

**Files:**
- Create: `src/main/java/se/alipsa/hfjinja/internal/ast/Statement.java`
- Create: `src/main/java/se/alipsa/hfjinja/internal/ast/Expression.java`
- Test: `src/test/java/se/alipsa/hfjinja/internal/ast/AstInventoryTest.java`

**Interfaces:**
- Produces: every node record used by Task 3 onward. Exact shapes below — later tasks construct these by name, so the names and component order are load-bearing.

**Fidelity rules for this task:**

1. **Every node carries `SourceLocation location()`** as its last component. Upstream nodes have no locations; ours do, per `req/project-description.md:147-149`. The location is the start of the token that began the node.
2. **`ObjectLiteral` uses `List<ObjectEntry>`**, per the settled decision above.
3. **Optional children are nullable, not `Optional`.** `SliceExpression.start/stop/step` mirror upstream `Expression | undefined` and `SetStatement.value` mirrors `Expression | null`. The parser is the only construction site, and a nullable component keeps the node shape readable at thirty nodes. Document each nullable component in javadoc; do *not* `requireNonNull` them.
4. **Defensive copies.** Every `List` component is wrapped with `List.copyOf` in the compact constructor, making the node genuinely immutable.
5. **`BinaryExpression.operator` and `UnaryExpression.operator` hold a `Token`**, matching upstream, which is why `internal.lexer.Token` is `public`.

- [ ] **Step 1: Write the failing inventory test**

```java
package se.alipsa.hfjinja.internal.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AstInventoryTest {
  private static final Pattern ALLOWLIST_KEY = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:");

  @Test
  void javaNodesCoverEveryUpstreamAstNode() throws IOException {
    var allowlist = Files.readString(
        Path.of("upstream/ast-allowlist.json"), StandardCharsets.UTF_8);
    var expected = new TreeSet<String>();
    var matcher = ALLOWLIST_KEY.matcher(allowlist);
    while (matcher.find()) {
      expected.add(matcher.group(1));
    }
    // Upstream's Statement, Expression, and Literal are abstract bases, modeled here as the two
    // sealed interfaces; they have no record of their own.
    expected.removeAll(Set.of("Statement", "Expression", "Literal"));

    var actual = new TreeSet<String>();
    actual.addAll(nestedRecordNames(Statement.class));
    actual.addAll(nestedRecordNames(Expression.class));
    actual.remove("ObjectEntry"); // a helper, not an upstream node

    assertEquals(expected, actual);
  }

  private static Set<String> nestedRecordNames(Class<?> owner) {
    return Arrays.stream(owner.getDeclaredClasses())
        .filter(Class::isRecord)
        .map(Class::getSimpleName)
        .collect(Collectors.toCollection(TreeSet::new));
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --offline --tests 'se.alipsa.hfjinja.internal.ast.AstInventoryTest'`
Expected: FAIL — `Statement` and `Expression` do not exist.

- [ ] **Step 3: Write `Statement.java`**

```java
package se.alipsa.hfjinja.internal.ast;

import java.util.List;
import java.util.Objects;
import se.alipsa.hfjinja.SourceLocation;

/**
 * A parsed node that produces no value of its own, ported from upstream {@code ast.ts}.
 *
 * <p>Upstream models {@code Expression} as a subclass of {@code Statement}, so an expression is
 * usable everywhere a statement is; {@link Expression} preserves that. Nodes are nested records
 * rather than one file each so the sealed hierarchy needs no cross-file {@code permits} list and
 * the file stays readable next to the 320-line upstream original.
 *
 * <p>Declared {@code public} because {@code internal.parser} needs cross-package access. It is
 * internal by package naming and API convention; Java module exports do not prevent classpath
 * consumers from accessing it.
 */
public sealed interface Statement permits Statement.Program, Statement.If, Statement.For,
    Statement.Break, Statement.Continue, Statement.SetStatement, Statement.Macro,
    Statement.Comment, Statement.FilterStatement, Statement.CallStatement, Expression {

  /** Returns the start of the token that began this node. */
  SourceLocation location();

  /** A whole template: upstream {@code Program}. */
  record Program(List<Statement> body, SourceLocation location) implements Statement {
    public Program {
      body = List.copyOf(body);
      Objects.requireNonNull(location, "location");
    }
  }

  /** Upstream {@code If}. {@code alternate} is empty when there is no elif/else branch. */
  record If(Expression test, List<Statement> body, List<Statement> alternate,
      SourceLocation location) implements Statement {
    public If {
      Objects.requireNonNull(test, "test");
      body = List.copyOf(body);
      alternate = List.copyOf(alternate);
      Objects.requireNonNull(location, "location");
    }
  }

  /**
   * Upstream {@code For}. {@code loopVariable} is always an {@link Expression.Identifier} or an
   * {@link Expression.TupleLiteral}; {@code defaultBlock} is the optional {@code else} body and is
   * empty when absent.
   */
  record For(Expression loopVariable, Expression iterable, List<Statement> body,
      List<Statement> defaultBlock, SourceLocation location) implements Statement {
    public For {
      Objects.requireNonNull(loopVariable, "loopVariable");
      Objects.requireNonNull(iterable, "iterable");
      body = List.copyOf(body);
      defaultBlock = List.copyOf(defaultBlock);
      Objects.requireNonNull(location, "location");
    }
  }

  /** Upstream {@code Break}. */
  record Break(SourceLocation location) implements Statement {
    public Break {
      Objects.requireNonNull(location, "location");
    }
  }

  /** Upstream {@code Continue}. */
  record Continue(SourceLocation location) implements Statement {
    public Continue {
      Objects.requireNonNull(location, "location");
    }
  }

  /**
   * Upstream {@code SetStatement}. {@code value} is {@code null} for the block form
   * ({@code {% set x %}…{% endset %}}), mirroring upstream's {@code Expression | null}; exactly one
   * of {@code value} and a non-empty {@code body} is present.
   */
  record SetStatement(Expression assignee, Expression value, List<Statement> body,
      SourceLocation location) implements Statement {
    public SetStatement {
      Objects.requireNonNull(assignee, "assignee");
      body = List.copyOf(body);
      Objects.requireNonNull(location, "location");
    }
  }

  /** Upstream {@code Macro}. */
  record Macro(Expression.Identifier name, List<Expression> args, List<Statement> body,
      SourceLocation location) implements Statement {
    public Macro {
      Objects.requireNonNull(name, "name");
      args = List.copyOf(args);
      body = List.copyOf(body);
      Objects.requireNonNull(location, "location");
    }
  }

  /** Upstream {@code Comment}: the raw comment text between <code>&#123;#</code> and <code>#&#125;</code>. */
  record Comment(String value, SourceLocation location) implements Statement {
    public Comment {
      Objects.requireNonNull(value, "value");
      Objects.requireNonNull(location, "location");
    }
  }

  /** Upstream {@code FilterStatement}: {@code filter} is an Identifier or a CallExpression. */
  record FilterStatement(Expression filter, List<Statement> body, SourceLocation location)
      implements Statement {
    public FilterStatement {
      Objects.requireNonNull(filter, "filter");
      body = List.copyOf(body);
      Objects.requireNonNull(location, "location");
    }
  }

  /** Upstream {@code CallStatement}. {@code callerArgs} is {@code null} when {@code {% call %}} declares none. */
  record CallStatement(Expression.CallExpression call, List<Expression> callerArgs,
      List<Statement> body, SourceLocation location) implements Statement {
    public CallStatement {
      Objects.requireNonNull(call, "call");
      callerArgs = callerArgs == null ? null : List.copyOf(callerArgs);
      body = List.copyOf(body);
      Objects.requireNonNull(location, "location");
    }
  }
}
```

- [ ] **Step 4: Write `Expression.java`**

Same package and conventions. The sealed `permits` list is implicit because every implementation is nested. Node shapes, in upstream's declaration order:

```java
public sealed interface Expression extends Statement {

  record MemberExpression(Expression object, Expression property, boolean computed,
      SourceLocation location) implements Expression { /* requireNonNull object, property, location */ }

  record CallExpression(Expression callee, List<Expression> args, SourceLocation location)
      implements Expression { /* copyOf args */ }

  record Identifier(String value, SourceLocation location) implements Expression {}

  record IntegerLiteral(long value, SourceLocation location) implements Expression {}

  record FloatLiteral(double value, SourceLocation location) implements Expression {}

  record StringLiteral(String value, SourceLocation location) implements Expression {}

  record ArrayLiteral(List<Expression> value, SourceLocation location) implements Expression {}

  record TupleLiteral(List<Expression> value, SourceLocation location) implements Expression {}

  /** One {@code key: value} pair. Upstream's identity-keyed Map is a list here so duplicate keys survive. */
  record ObjectEntry(Expression key, Expression value) {}

  record ObjectLiteral(List<ObjectEntry> value, SourceLocation location) implements Expression {}

  record BinaryExpression(Token operator, Expression left, Expression right,
      SourceLocation location) implements Expression {}

  record FilterExpression(Expression operand, Expression filter, SourceLocation location)
      implements Expression {}

  record SelectExpression(Expression lhs, Expression test, SourceLocation location)
      implements Expression {}

  record TestExpression(Expression operand, boolean negate, Identifier test,
      SourceLocation location) implements Expression {}

  record UnaryExpression(Token operator, Expression argument, SourceLocation location)
      implements Expression {}

  /** Upstream {@code SliceExpression}: any of {@code start}, {@code stop}, {@code step} may be null. */
  record SliceExpression(Expression start, Expression stop, Expression step,
      SourceLocation location) implements Expression {}

  record KeywordArgumentExpression(Identifier key, Expression value, SourceLocation location)
      implements Expression {}

  record SpreadExpression(Expression argument, SourceLocation location) implements Expression {}

  record Ternary(Expression condition, Expression trueExpr, Expression falseExpr,
      SourceLocation location) implements Expression {}
}
```

The block above is a **shape table, not finished code**: it omits the compact constructors and the file header. Fill each one in following the `Statement.java` pattern — `List.copyOf` for list components, `Objects.requireNonNull` for every non-nullable reference component, and nothing for the documented nullable ones (`SliceExpression`'s three). The file needs `package se.alipsa.hfjinja.internal.ast;`, imports for `java.util.List`, `java.util.Objects`, `se.alipsa.hfjinja.SourceLocation`, and `se.alipsa.hfjinja.internal.lexer.Token` (used by `BinaryExpression` and `UnaryExpression` — this is the cross-package need that makes `Token` `public`), and the same `public sealed interface` javadoc treatment as `Statement`.

**`IntegerLiteral` is `long`, `FloatLiteral` is `double`.** Upstream stores both as JS `number`. WP2 already established the split integer/float value model (`req/implementation-plan.md`, WP2 step 1), and the lexer hands the parser a digit string; `long` keeps integer literals exact through parsing. Task 4 specifies the overflow behavior.

- [ ] **Step 5: Run the inventory test**

Run: `./gradlew test --offline --tests 'se.alipsa.hfjinja.internal.ast.AstInventoryTest'`
Expected: PASS. If it fails, the diff names exactly which node is missing or extra.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/se/alipsa/hfjinja/internal/ast src/test/java/se/alipsa/hfjinja/internal/ast
git commit -m "Model the upstream AST as sealed Java records"
```

---

### Task 3: Parser skeleton — cursor, located errors, depth guard, program loop

**Files:**
- Create: `src/main/java/se/alipsa/hfjinja/internal/parser/Parser.java`
- Test: `src/test/java/se/alipsa/hfjinja/internal/parser/ParserTest.java`

**Interfaces:**
- Consumes: `Lexer.tokenize(String, TemplateOptions)`, `Token(TokenType, String, SourceLocation)`, all Task 2 node records.
- Produces: `public static Statement.Program Parser.parse(List<Token> tokens, TemplateOptions options)` — the single entry point Tasks 4–6 extend and Task 8 wires into `Template`.

**The bounds problem, and why it comes first.** Upstream indexes `tokens[current]` unchecked. In JS an out-of-range read yields `undefined`, and the following `.type` throws a `TypeError` — which upstream's own tests accept, since they only assert `toThrowError()`. In Java the same code throws `IndexOutOfBoundsException`, which is not an `HfJinjaException` and carries no location. Every token access must therefore go through bounds-checked helpers that raise a located `TemplateSyntaxException` instead. Verified upstream behavior to match: `{{ variable }}{{` throws, `{% if condition %}\n    Content` throws, `{% if c %}x{% endif %}{% endfor %}` throws.

- [ ] **Step 1: Write the failing tests**

```java
package se.alipsa.hfjinja.internal.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import se.alipsa.hfjinja.ErrorCategory;
import se.alipsa.hfjinja.TemplateOptions;
import se.alipsa.hfjinja.TemplateSyntaxException;
import se.alipsa.hfjinja.internal.ast.Statement;
import se.alipsa.hfjinja.internal.lexer.Lexer;
import org.junit.jupiter.api.Test;

class ParserTest {
  @Test
  void parsesTextAndCommentIntoAProgram() {
    var program = parse("a{# note #}b");
    assertEquals(3, program.body().size());
    assertInstanceOf(Statement.Comment.class, program.body().get(1));
    assertEquals(" note ", ((Statement.Comment) program.body().get(1)).value());
  }

  @Test
  void reportsAnUnexpectedEndOfInputAsALocatedSyntaxError() {
    // upstream/vendor/test/templates.test.js:5462 "Unclosed statement"
    var thrown = assertThrows(TemplateSyntaxException.class, () -> parse("{{ variable }}{{"));
    assertEquals(ErrorCategory.SYNTAX, thrown.category());
    assertEquals(14, thrown.location().orElseThrow().offset());
  }

  static Statement.Program parse(String source) {
    return parse(source, RAW);
  }

  static Statement.Program parse(String source, TemplateOptions options) {
    return Parser.parse(Lexer.tokenize(source, options), options);
  }

  /** Raw scanning keeps grammar assertions independent of the trim/lstrip defaults. */
  static final TemplateOptions RAW = TemplateOptions.builder().build();
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --offline --tests 'se.alipsa.hfjinja.internal.parser.ParserTest'`
Expected: FAIL — `Parser` does not exist.

- [ ] **Step 3: Implement the skeleton**

```java
package se.alipsa.hfjinja.internal.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import se.alipsa.hfjinja.ErrorCategory;
import se.alipsa.hfjinja.SourceLocation;
import se.alipsa.hfjinja.TemplateOptions;
import se.alipsa.hfjinja.TemplateRenderException;
import se.alipsa.hfjinja.TemplateSyntaxException;
import se.alipsa.hfjinja.internal.ast.Expression;
import se.alipsa.hfjinja.internal.ast.Statement;
import se.alipsa.hfjinja.internal.lexer.Token;
import se.alipsa.hfjinja.internal.lexer.TokenType;

/**
 * Recursive-descent parser ported from upstream {@code parser.ts}, one method per upstream
 * function and in the same order, so the two files can be diffed side by side.
 *
 * <p>Upstream reads {@code tokens[current]} without bounds checks and relies on JS returning
 * {@code undefined}; the resulting {@code TypeError} is what its tests assert. Java would throw an
 * unlocated {@link IndexOutOfBoundsException} instead, so every read here goes through
 * {@link Cursor#peek()} / {@link Cursor#next()} and surfaces as a located
 * {@link TemplateSyntaxException} with the same {@link ErrorCategory#SYNTAX} outcome.
 */
public final class Parser {
  private Parser() {}

  public static Statement.Program parse(List<Token> tokens, TemplateOptions options) {
    Objects.requireNonNull(tokens, "tokens");
    Objects.requireNonNull(options, "options");
    return new Cursor(tokens, options).parseProgram();
  }

  private static final class Cursor {
    private final List<Token> tokens;
    private final TemplateOptions options;
    private final SourceLocation endLocation;
    private int current;
    private int depth;

    Cursor(List<Token> tokens, TemplateOptions options) {
      this.tokens = tokens;
      this.options = options;
      this.endLocation = tokens.isEmpty()
          ? new SourceLocation(0, 1, 1)
          : tokens.get(tokens.size() - 1).start();
    }

    Statement.Program parseProgram() {
      var body = new ArrayList<Statement>();
      var start = tokens.isEmpty() ? endLocation : tokens.get(0).start();
      while (current < tokens.size()) {
        body.add(parseAny());
      }
      return new Statement.Program(body, start);
    }

    /** Upstream {@code parseAny}. */
    Statement parseAny() {
      return switch (peek().type()) {
        case Comment -> {
          var token = next();
          yield new Statement.Comment(token.value(), token.start());
        }
        case Text -> parseText();
        case OpenStatement -> parseJinjaStatement();
        case OpenExpression -> parseJinjaExpression();
        default -> throw syntaxError("Unexpected token type: " + peek().type(), peek().start());
      };
    }

    /** Upstream {@code parseText}. */
    Expression.StringLiteral parseText() {
      var token = expect(TokenType.Text, "Expected text token");
      return new Expression.StringLiteral(token.value(), token.start());
    }

    // ---- cursor primitives -------------------------------------------------

    /** The token at the cursor, or a located syntax error at end of input. */
    Token peek() {
      if (current >= tokens.size()) {
        throw syntaxError("Unexpected end of template", endLocation);
      }
      return tokens.get(current);
    }

    /** Upstream's {@code tokens[current++]}, bounds-checked. */
    Token next() {
      var token = peek();
      ++current;
      return token;
    }

    /** Upstream {@code expect}. */
    Token expect(TokenType type, String error) {
      if (current >= tokens.size()) {
        throw syntaxError("Parser Error: " + error + ". End of template !== " + type, endLocation);
      }
      var previous = tokens.get(current++);
      if (previous.type() != type) {
        throw syntaxError(
            "Parser Error: " + error + ". " + previous.type() + " !== " + type, previous.start());
      }
      return previous;
    }

    /** Upstream {@code is}. Absent tokens simply do not match, as in JS. */
    boolean is(TokenType... types) {
      if (current + types.length > tokens.size()) {
        return false;
      }
      for (var i = 0; i < types.length; i++) {
        if (tokens.get(current + i).type() != types[i]) {
          return false;
        }
      }
      return true;
    }

    /** Upstream {@code isStatement}: <code>&#123;%</code> followed by one of the named identifiers. */
    boolean isStatement(String... names) {
      if (current + 1 >= tokens.size()
          || tokens.get(current).type() != TokenType.OpenStatement
          || tokens.get(current + 1).type() != TokenType.Identifier) {
        return false;
      }
      var value = tokens.get(current + 1).value();
      for (var name : names) {
        if (name.equals(value)) {
          return true;
        }
      }
      return false;
    }

    /** Upstream {@code isIdentifier}: the next N tokens are exactly these identifier values. */
    boolean isIdentifier(String... names) {
      if (current + names.length > tokens.size()) {
        return false;
      }
      for (var i = 0; i < names.length; i++) {
        var token = tokens.get(current + i);
        if (token.type() != TokenType.Identifier || !names[i].equals(token.value())) {
          return false;
        }
      }
      return true;
    }

    /** Upstream {@code expectIdentifier}. */
    void expectIdentifier(String name) {
      if (!isIdentifier(name)) {
        throw syntaxError("Expected " + name, locationHere());
      }
      ++current;
    }

    SourceLocation locationHere() {
      return current < tokens.size() ? tokens.get(current).start() : endLocation;
    }

    // ---- depth guard -------------------------------------------------------

    /**
     * Runs {@code production} one AST level deeper, failing as {@code RESOURCE_LIMIT} before the
     * JVM can overflow its stack. Checked on entry rather than at node construction, which happens
     * on the way back out of the recursion and so would be too late to prevent the overflow.
     *
     * <p>Call this only at a genuine nesting point — see the table in Task 5. Wrapping every
     * precedence method instead would charge roughly thirteen units for one pair of parentheses,
     * because a single expression always descends the full chain from {@code parseExpression} to
     * {@code parsePrimaryExpression}; {@code maxAstDepth} would then reject about twenty nested
     * parentheses while claiming a limit of 256.
     */
    <T> T nested(java.util.function.Supplier<T> production) {
      if (++depth > options.maxAstDepth()) {
        --depth;
        throw new TemplateRenderException(
            "AST depth exceeds the configured limit of " + options.maxAstDepth(),
            ErrorCategory.RESOURCE_LIMIT,
            locationHere());
      }
      try {
        return production.get();
      } finally {
        --depth;
      }
    }

    static TemplateSyntaxException syntaxError(String message, SourceLocation location) {
      return new TemplateSyntaxException(message, location);
    }
  }
}
```

Add temporary `parseJinjaStatement`/`parseJinjaExpression` stubs that throw `UnsupportedOperationException`; Tasks 4–6 replace them.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --offline --tests 'se.alipsa.hfjinja.internal.parser.ParserTest'`
Expected: PASS both tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/se/alipsa/hfjinja/internal/parser src/test/java/se/alipsa/hfjinja/internal/parser
git commit -m "Add the parser skeleton with bounds-checked, located token access"
```

---

### Task 4: Primary expressions and literals

**Files:**
- Modify: `src/main/java/se/alipsa/hfjinja/internal/parser/Parser.java`
- Test: `src/test/java/se/alipsa/hfjinja/internal/parser/ParserTest.java`

**Interfaces:**
- Produces: `Expression parsePrimaryExpression()`, `Expression parseExpressionSequence(boolean primary)` — used by Tasks 5 and 6.

Port `parser.ts:596-668` (`parsePrimaryExpression`) and `parser.ts:271-284` (`parseExpressionSequence`).

**Fidelity notes — port these exactly, do not improve:**

- **`{{ -x }}` is a syntax error upstream.** The lexer folds `-` into a numeric literal only when digits follow (`Lexer.java:280-289`), otherwise emitting a `UnaryOperator` token. `parsePrimaryExpression` has no `UnaryOperator` case, so it falls to `default` and throws. Verified against the pinned package: `{{ -5 }}` → `IntegerLiteral`; `{{ -x }}` and `{{ +x }}` → `SyntaxError: Unexpected token: UnaryOperator`. Port the throw.
- **Adjacent string literals concatenate**: `{{ 'a' 'b' }}` is one `StringLiteral` with value `ab` (`parser.ts:606-612`).
- **Integer vs float is decided by a literal `.`**, not by value: `num.includes(".") ? FloatLiteral : IntegerLiteral` (`parser.ts:601`). `1e3` has no dot and is therefore an *integer* literal upstream — check what the lexer emits for it and mirror whatever `Number(num)` would produce.
- **Overflow:** parse integer text with `Long.parseLong`; on `NumberFormatException`, fall back to `FloatLiteral(Double.parseDouble(text))`, matching JS's single `number` type, which silently loses precision rather than failing. Add a test for a 25-digit literal.
- **`parseExpressionSequence` returns a bare expression when there is no comma**, and a `TupleLiteral` when there is — including the trailing-comma case `(a,)`.
- The `OpenParen` branch's upstream error message contains a literal, un-interpolated `${tokens[current].type}` because it uses single quotes (`parser.ts:618`). Reproduce the message text as-is; it is what the corpus classifier will see.

- [ ] **Step 1: Write the failing tests** — one per fidelity note above, e.g.:

```java
  @Test
  void concatenatesAdjacentStringLiterals() {
    var literal = (Expression.StringLiteral) soleExpression("{{ 'a' 'b' 'c' }}");
    assertEquals("abc", literal.value());
  }

  @Test
  void rejectsAUnaryOperatorInPrimaryPosition() {
    // Upstream throws "Unexpected token: UnaryOperator"; {{ -5 }} lexes as a numeric literal
    // and is accepted, but {{ -x }} is not. Verified against @huggingface/jinja 0.5.9.
    assertInstanceOf(Expression.IntegerLiteral.class, soleExpression("{{ -5 }}"));
    assertThrows(TemplateSyntaxException.class, () -> parse("{{ -x }}"));
  }

  @Test
  void keepsDuplicateObjectLiteralKeys() {
    // Upstream keys its object Map by AST node identity, so both entries survive (size 2).
    var object = (Expression.ObjectLiteral) soleExpression("{{ {'a': 1, 'a': 2} }}");
    assertEquals(2, object.value().size());
  }
```

Add a `soleExpression(String)` helper that parses and returns `program.body().get(0)` cast to `Expression`.

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew test --offline --tests '*ParserTest'` — Expected: FAIL, `UnsupportedOperationException` from the Task 3 stubs.
- [ ] **Step 3: Implement** `parsePrimaryExpression`, `parseExpressionSequence`, and `parseJinjaExpression` (`parser.ts:190-198`: expect `OpenExpression`, parse, expect `CloseExpression`). Wrap the three recursive branches of `parsePrimaryExpression` — `OpenParen`, `OpenSquareBracket`, `OpenCurlyBracket` — in `nested(...)`, and nothing else in this task; Task 5's table is the complete list of guard sites.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "Port primary expressions and literals"`

---

### Task 5: Operator precedence chain

**Files:**
- Modify: `src/main/java/se/alipsa/hfjinja/internal/parser/Parser.java`
- Test: `src/test/java/se/alipsa/hfjinja/internal/parser/ParserTest.java`

**Interfaces:**
- Produces: `Expression parseExpression()` — the entry point Task 6's statements call.

Port `parser.ts:326-595` in upstream's own order, one Java method per upstream function.

**Where the depth guard goes — and where it must not.** `nested(...)` counts AST nesting levels, so it wraps only the productions that re-enter the expression or statement grammar from inside an already-open one. It must **not** wrap the precedence chain itself: `parseExpression` → `parseIfExpression` → `parseLogicalOrExpression` → `parseLogicalAndExpression` → `parseLogicalNegationExpression` → `parseComparisonExpression` → `parseAdditiveExpression` → `parseMultiplicativeExpression` → `parseTestExpression` → `parseFilterExpression` → `parseCallMemberExpression` → `parsePrimaryExpression` is twelve methods traversed on *every* expression, plus `parseExpressionSequence` on the way back in, so wrapping each would cost thirteen depth units per parenthesis. `((((1))))` has an AST depth of **one** — parentheses build no node at all — so charging thirteen for each would make the option's name a lie and reject valid shallow input at about twenty parentheses.

Increment at exactly these sites, and nowhere else:

| Site | Upstream line | Nesting it represents |
| --- | --- | --- |
| `parseAny` | 62 | statement-block nesting (`if`/`for`/`macro`/`call`/`filter`/`set` bodies) |
| `parsePrimaryExpression`, `OpenParen` branch | 615 | `(((x)))` |
| `parsePrimaryExpression`, `OpenSquareBracket` branch | 621 | `[[[x]]]` |
| `parsePrimaryExpression`, `OpenCurlyBracket` branch | 635 | `{'a': {'b': …}}` |
| `parseArgumentsList` | 447 | `f(g(h(x)))` |
| `parseMemberExpressionArgumentsList` | 481 | `a[b[c]]` |
| `parseCallExpression` | 427 | `f()()` |
| `parseLogicalNegationExpression`, the `not` recursion only | 375 | `not not not x` |
| `parseIfExpression`, the false-branch recursion only | 341 | chained ternaries |

Every one of those units still costs at most the thirteen-frame chain, so the default limit of 256 bounds the parser at roughly 3,300 frames — well inside a default JVM stack, which is the property the guard exists to provide.

Method table:

| Upstream function | Line | Notes |
| --- | --- | --- |
| `parseExpression` | 326 | delegates to `parseIfExpression` |
| `parseIfExpression` | 331 | `x if c else y` → `Ternary`; `x if c` with no `else` → `SelectExpression`; recurses on the false branch so ternaries chain right |
| `parseLogicalOrExpression` | 350 | left-associative `or` |
| `parseLogicalAndExpression` | 361 | left-associative `and` |
| `parseLogicalNegationExpression` | 372 | `not` loop; returns the *last* built `UnaryExpression`, so `not not x` yields one level — port the quirk |
| `parseComparisonExpression` | 386 | `not in` synthesizes a `Token("not in", Identifier)`; give it the location of the `not` token |
| `parseAdditiveExpression` | 407 | `+ - ~` |
| `parseMultiplicativeExpression` | 545 | `* / %`, binding *tighter* than tests |
| `parseTestExpression` | 559 | `is` / `is not`, chained |
| `parseFilterExpression` | 580 | `|` chain, optional call arguments |
| `parseCallMemberExpression` | 417 | |
| `parseCallExpression` | 427 | supports `f()()` and `f().y` |
| `parseArgs` / `parseArgumentsList` | 439 / 447 | `*spread`, `key=value` keyword args |
| `parseMemberExpressionArgumentsList` | 481 | slices `[a:b:c]`, and the `[]` / >3-part errors |
| `parseMemberExpression` | 514 | `.name` and `[expr]`; dot property must be Identifier or IntegerLiteral |

**Precedence traps upstream comments call out and the tests must lock in:**
- Membership shares precedence with comparison: `('a' in 'apple' == 'b' in 'banana')` groups as `('a' in ('apple' == ('b' in 'banana')))` (`parser.ts:387-388`).
- Multiplicative binds tighter than `is`: `4 * 4 is divisibleby(2)` groups as `4 * (4 is divisibleby(2))` (`parser.ts:547-548`).
- `not test.x` is `not (test.x)` (`parser.ts:377`).

**Synthesized `not in` token.** Upstream builds `new Token("not in", TOKEN_TYPES.Identifier)` with no position. Ours needs a `SourceLocation`; use the `not` token's start so the operator points at where the operator begins.

- [ ] **Step 1: Write the failing precedence tests**

Assert on nested record types and operator values. Cover each trap above:

```java
  @Test
  void membershipSharesPrecedenceWithComparison() {
    // parser.ts:387-388: ('a' in 'apple' == 'b' in 'banana') groups right-nested.
    var outer = (Expression.BinaryExpression) soleExpression("{{ 'a' in 'apple' == 'b' in 'banana' }}");
    assertEquals("in", outer.operator().value());
    var middle = (Expression.BinaryExpression) outer.right();
    assertEquals("==", middle.operator().value());
    assertEquals("in", ((Expression.BinaryExpression) middle.right()).operator().value());
  }

  @Test
  void multiplicativeBindsTighterThanTests() {
    // parser.ts:547-548: 4 * 4 is divisibleby(2) groups as 4 * (4 is divisibleby(2)).
    var product = (Expression.BinaryExpression) soleExpression("{{ 4 * 4 is divisibleby(2) }}");
    assertEquals("*", product.operator().value());
    assertInstanceOf(Expression.TestExpression.class, product.right());
  }

  @Test
  void negationAppliesToTheWholeMemberExpression() {
    // parser.ts:377: not test.x parses as not (test.x).
    var unary = (Expression.UnaryExpression) soleExpression("{{ not test.x }}");
    assertInstanceOf(Expression.MemberExpression.class, unary.argument());
  }

  @Test
  void ternariesChainThroughTheFalseBranch() {
    var ternary = (Expression.Ternary) soleExpression("{{ 1 if a else 2 if b else 3 }}");
    assertInstanceOf(Expression.Ternary.class, ternary.falseExpr());
  }

  @Test
  void anIfWithoutElseIsASelectExpression() {
    assertInstanceOf(Expression.SelectExpression.class, soleExpression("{{ xs if a }}"));
  }

  @Test
  void notInSynthesizesASingleOperatorLocatedAtTheNotToken() {
    var binary = (Expression.BinaryExpression) soleExpression("{{ a not in b }}");
    assertEquals("not in", binary.operator().value());
    assertEquals(5, binary.operator().start().offset()); // the 'n' of 'not'
  }

  @Test
  void parsesSliceForms() {
    assertSlice("{{ a[1:2:3] }}", true, true, true);
    assertSlice("{{ a[:2] }}", false, true, false);
    assertSlice("{{ a[1:] }}", true, false, false);
    assertSlice("{{ a[::2] }}", false, false, true);
  }

  @Test
  void rejectsMalformedMemberAndSliceExpressions() {
    assertThrows(TemplateSyntaxException.class, () -> parse("{{ a[] }}"));
    assertThrows(TemplateSyntaxException.class, () -> parse("{{ a[1:2:3:4] }}"));
    assertThrows(TemplateSyntaxException.class, () -> parse("{{ a.'b' }}"));
  }

  private static void assertSlice(String source, boolean start, boolean stop, boolean step) {
    var member = (Expression.MemberExpression) soleExpression(source);
    var slice = (Expression.SliceExpression) member.property();
    assertEquals(start, slice.start() != null);
    assertEquals(stop, slice.stop() != null);
    assertEquals(step, slice.step() != null);
  }
```

Also cover chained filters (`{{ a|b|c }}`), chained tests (`{{ a is x is y }}`), `f().y()`, keyword arguments (`{{ f(x=1) }}`), and spread arguments (`{{ f(*xs) }}`).
- [ ] **Step 2: Run to verify they fail.**
- [ ] **Step 3: Implement the fifteen methods** in the table order.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "Port the expression precedence chain"`

---

### Task 6: Statements

**Files:**
- Modify: `src/main/java/se/alipsa/hfjinja/internal/parser/Parser.java`
- Test: `src/test/java/se/alipsa/hfjinja/internal/parser/ParserTest.java`

Port `parser.ts:100-325`: `parseJinjaStatement` and its per-keyword branches.

| Keyword | Upstream line | Notes |
| --- | --- | --- |
| `set` | 201 | inline `set x = v` **and** block `{% set x %}…{% endset %}`; `value` is null in the block form |
| `if` | 222 | `elif` recurses into `parseIfStatement`, producing a nested `If` inside `alternate` |
| `macro` | 259 | name must be an Identifier |
| `for` | 287 | loop variable must be Identifier or TupleLiteral; requires the `in` keyword; optional `else` block |
| `call` | 137 | optional `call(args)` caller list; callee must be an Identifier |
| `break` / `continue` | 165 / 170 | |
| `filter` | 175 | filter may take call arguments |
| unknown | 186 | `Unknown statement type: <name>` |

**Location for each statement node:** the `{%` token that opened it. Capture it before consuming.

- [ ] **Step 1: Write the failing tests**, including the three upstream parser-error fixtures (`templates.test.js:5461-5478`), each asserting `ErrorCategory.SYNTAX` and a location:
  - `{{ variable }}{{` — unclosed statement (already covered in Task 3; keep it)
  - `{% if condition %}\n    Content` — unclosed expression
  - `{% if condition %}\n    Content\n{% endif %}\n{% endfor %}` — unmatched control structure
  - `{% for %}` — missing loop variable
  Plus happy-path structure tests for every keyword in the table.
- [ ] **Step 2: Run to verify they fail.**
- [ ] **Step 3: Implement** `parseJinjaStatement`, `parseSetStatement`, `parseIfStatement`, `parseMacroStatement`, `parseForStatement`, and the inline `call`/`filter`/`break`/`continue` branches.
- [ ] **Step 4: Run the full suite.** Run: `./gradlew test --offline` — Expected: PASS.
- [ ] **Step 5: Add the depth-limit test**

```java
  @Test
  void deeplyNestedExpressionsFailAsAResourceLimitRatherThanOverflowingTheStack() {
    var source = "{{ " + "(".repeat(5_000) + "1" + ")".repeat(5_000) + " }}";
    var thrown = assertThrows(
        TemplateRenderException.class, () -> parse(source, TemplateOptions.builder().build()));
    assertEquals(ErrorCategory.RESOURCE_LIMIT, thrown.category());
  }

  @Test
  void nestingWellInsideTheLimitIsAccepted() {
    // The guard counts AST levels, not precedence dispatches. 200 parentheses is one AST level
    // per paren and must parse; a guard that charged the full ~13-method chain per paren would
    // reject this at roughly 20.
    var source = "{{ " + "(".repeat(200) + "1" + ")".repeat(200) + " }}";
    assertInstanceOf(Expression.IntegerLiteral.class, soleExpression(source));
  }
```

If the first test throws `StackOverflowError` instead, the depth guard is being checked too late — move the check to recursion entry, not node construction. If the second test throws `RESOURCE_LIMIT`, the guard is on too many methods — reduce it to the site table in Task 5.

- [ ] **Step 6: Add the termination property test**

`req/implementation-plan.md` WP3 lists "lexer/parser termination properties" as required test coverage. The property: for *any* input, the parser terminates with either a `Program` or an `HfJinjaException` — never a hang, a `StackOverflowError`, or a raw JDK exception leaking through the bounds checks.

```java
  @Test
  void parserTerminatesOnArbitraryInputWithAProgramOrADocumentedFailure() {
    var fragments = new String[] {
      "{{", "}}", "{%", "%}", "{#", "#}", "(", ")", "[", "]", "{", "}", ",", ".", ":", "|",
      "=", "==", "<", "+", "-", "*", "/", "%", "~", "if", "else", "elif", "endif", "for",
      "in", "endfor", "set", "endset", "macro", "endmacro", "call", "endcall", "filter",
      "endfilter", "break", "continue", "not", "and", "or", "is", "'s'", "1", "1.5", "x", " "
    };
    var random = new java.util.Random(20260820L);
    for (var i = 0; i < 20_000; i++) {
      var source = new StringBuilder();
      for (var j = 0; j < 1 + random.nextInt(24); j++) {
        source.append(fragments[random.nextInt(fragments.length)]);
      }
      var text = source.toString();
      assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
        try {
          parse(text);
        } catch (HfJinjaException expected) {
          // A located, categorized failure is a valid outcome for malformed input.
        }
      }, () -> "did not terminate cleanly for " + text);
    }
  }
```

Any `StackOverflowError`, `IndexOutOfBoundsException`, `NullPointerException`, or `ClassCastException` escaping this loop is a bug in the bounds checks or the depth guard — fix the parser, do not widen the catch.

- [ ] **Step 7: Commit** — `git commit -m "Port Jinja statement parsing"`

---

### Task 7: Differential AST-snapshot harness

**Files:**
- Create: `tools/ast-snapshot/snapshot.mjs`
- Create: `src/test/java/se/alipsa/hfjinja/internal/parser/AstSnapshot.java`
- Create: `src/test/java/se/alipsa/hfjinja/internal/parser/AstSnapshotDifferentialTest.java`
- Create: `src/test/resources/ast-snapshots/upstream-parsed.txt`
- Modify: `build.gradle`

**Interfaces:**
- Produces: `static String AstSnapshot.of(Statement node)` — a canonical, deterministic serialization.

`parse` is exported from the pinned package (`upstream/vendor/dist/index.d.ts`), so the parser can be differentially tested the same way the lexer was. This is what gate G3's "compare deterministic AST snapshots where meaningful" asks for.

**Canonical format.** One node per line, two-space indent per depth, no locations (upstream has none):

```
Program
  If
    BinaryExpression op=Identifier:"in"
      Identifier "a"
      Identifier "b"
    body
      StringLiteral "yes"
    alternate
```

Rules that keep Node and Java byte-identical:
- Node name is the upstream class name (`Statement.SetStatement` prints as `Set`? **No** — print the Java record's simple name and map upstream's `SetStatement`/`Set` mismatch in the Node script, which is the side that knows `node.type`). Emit `SetStatement`, not `Set`.
- Strings use JSON escaping (`JSON.stringify` on the Node side; a small escaper on the Java side covering `\` `"` `\n` `\r` `\t` and `\uXXXX` for anything below U+0020).
- Numbers: integers print as decimal with no exponent; floats print via JS `Number.prototype.toString` semantics. **Restrict the fixture set to literals where both sides agree** and add a comment saying so — full JS number formatting is WP4 work, not this slice's.
- `null`/absent optional children print as a bare `-` line.
- List-valued components print a label line (`body`, `alternate`, `args`, …) followed by their indented children, so an empty list is visible as a bare label.

- [ ] **Step 1: Write `tools/ast-snapshot/snapshot.mjs`**

Reads `--fixtures <path>`, a JSON array of objects in this exact schema — the same file the Java test reads, both sides taking the template from `.source` and identifying it by `.name`:

```json
[
  { "name": "NO_TEMPLATE", "source": "Hello world!", "upstreamLine": 10 },
  { "name": "TEXT_NODES", "source": "0{{ 'A' }}1{{ 'B' }}{{ 'C' }}2{{ 'D' }}3", "upstreamLine": 11 }
]
```

`name` is the upstream case name — the shared `TEST_STRINGS`/`TEST_PARSED` key. It is what both consumers key on and what every failure message must quote, because "fixture 47 diverged" sends a reader hunting while `LOGICAL_NOT_NOT diverged` does not. Names are unique (they are object keys upstream); the extractor in Step 2 must fail loudly on a duplicate. `upstreamLine` is provenance for reviewers and is ignored by both consumers.

For each entry the script calls `tokenize(entry.source, {lstrip_blocks: true, trim_blocks: true})` then `parse`, and writes `=== <name> <JSON.stringify(entry.source)>` followed by the serialization. **Key the block header on the name, not an index**, so adding one upstream fixture does not renumber every block after it and turn a one-line oracle diff into a whole-file rewrite. It must refuse to run on the wrong Node version by reusing `tools/corpus/check-node-version.mjs`'s check against `upstream/upstream-lock.json`, matching how the existing corpus tasks pin the oracle.

- [ ] **Step 2: Extract the fixture list**

**`TEST_PARSED` holds token arrays, not template text.** `upstream/vendor/test/templates.test.js` keeps two parallel maps: `TEST_STRINGS` (`:8`) maps a case name to its **source string**, and `TEST_PARSED` (`:250`) maps the same name to its **expected token array**. The lexing suite (`:5375-5388`) iterates `TEST_STRINGS`, tokenizes, and compares against `TEST_PARSED`; the parsing suite (`:5394-5397`) iterates `TEST_PARSED` and passes each *token array* straight into `parse`, never re-lexing. So there is no template text in `TEST_PARSED` to take.

Build `fixtures.json` like this:

1. Take the key set of `TEST_PARSED` intersected with the key set of `TEST_STRINGS` — every name that has both a source and a parse expectation. Both maps currently hold the same 159 keys, so the intersection is all 159; compute it rather than assuming, so an upstream sync that adds a key to one map only shrinks the fixture set instead of crashing the extractor.
2. For each key, take the **source** from `TEST_STRINGS[key]`.
3. Emit one object per key in the Task 7 schema: `name` is the key itself, `source` is `TEST_STRINGS[key]`, and `upstreamLine` is the line of that key's entry **in `TEST_STRINGS`** (the line the source text appears on — `NO_TEMPLATE` is `:10`, `TEXT_NODES` is `:11`). Sort by name so the file has a stable, reviewable order independent of upstream's declaration order.

**What this harness does and does not claim.** It is not a reproduction of the upstream `TEST_PARSED` token fixture, and must not be described as one — upstream's lexing suite calls `tokenize(text)` with no options, so those token arrays reflect `trim_blocks`/`lstrip_blocks` both **off**. This harness deliberately tokenizes with both **on**, matching upstream's public `Template` constructor (`index.ts:26-29`) and therefore the `TemplateOptions.DEFAULT` settled in Task 1. What it verifies is that Java and the pinned Node package produce the *same AST from the same source under the same preprocessing* — which is the parity that matters for WP4. Both sides run the identical pipeline, so the comparison stays sound; only the provenance claim would be wrong.

- [ ] **Step 3: Generate the Node snapshots**

```bash
node tools/ast-snapshot/snapshot.mjs --fixtures src/test/resources/ast-snapshots/fixtures.json \
  > src/test/resources/ast-snapshots/upstream-parsed.txt
```

Commit the output. It is a generated oracle artifact, regenerated only by an explicit upstream sync.

- [ ] **Step 4: Write `AstSnapshot.java` and the failing differential test**

`AstSnapshotDifferentialTest` reads `fixtures.json` (taking each entry's `.name` and `.source`, ignoring `upstreamLine`) and `upstream-parsed.txt`, parses each fixture with `TemplateOptions.DEFAULT` (both flags on, matching what the Node script passes), serializes it, and asserts equality per fixture.

Two requirements on the comparison:

- **Pair blocks by name, never by position.** Assert first that the fixture name set and the snapshot name set are equal, failing with the symmetric difference — a fixture with no oracle block is a stale-oracle bug, and an oracle block with no fixture means the extractor dropped a case. Position-based pairing would silently compare the wrong templates after any insertion.
- **Quote the name and the source in every failure message**, e.g. `LOGICAL_NOT_NOT ("{{ not not true }}{{ not not false }}")`, followed by the expected and actual serializations.

- [ ] **Step 5: Run it** — Run: `./gradlew test --offline --tests '*AstSnapshotDifferentialTest'`. Expected: FAIL initially on any real divergence. **Every divergence is a parser bug or a serializer bug — fix the Java side, never the checked-in oracle**, unless you can show the serializer itself is asymmetric.
- [ ] **Step 6: Add the Gradle task**

```groovy
def astSnapshotMarker = layout.buildDirectory.file('astSnapshotVerify/verified').get().asFile
tasks.register('astSnapshotVerify', Exec) {
  group = 'verification'
  description = 'Regenerates upstream AST snapshots and fails if the checked-in oracle is stale.'
  executable = 'node'
  args 'tools/ast-snapshot/snapshot.mjs',
      '--fixtures', 'src/test/resources/ast-snapshots/fixtures.json',
      '--check', 'src/test/resources/ast-snapshots/upstream-parsed.txt',
      '--lock', 'upstream/upstream-lock.json'
  inputs.files('tools/ast-snapshot/snapshot.mjs',
      'src/test/resources/ast-snapshots/fixtures.json',
      'src/test/resources/ast-snapshots/upstream-parsed.txt',
      'upstream/vendor/dist/index.js', 'upstream/upstream-lock.json')
  outputs.file(astSnapshotMarker).withPropertyName('verifiedMarker')
  doLast {
    astSnapshotMarker.parentFile.mkdirs()
    astSnapshotMarker.text = 'verified\n'
  }
}
```

Add `astSnapshotVerify` to the `check` dependency list, and add it to both `each` blocks near the bottom of `build.gradle` that wire `dependsOn upstreamVerification`, `mustRunAfter`, a timeout (`astSnapshotVerify: 120`), and `dependsOn nodeVersionVerification`.

- [ ] **Step 7: Verify the whole build** — Run: `./gradlew check` (needs Node; `--offline` for the Java-only parts). Expected: BUILD SUCCESSFUL.
- [ ] **Step 8: Commit** — `git commit -m "Add a differential AST snapshot harness against the pinned Node parser"`

---

### Task 8: Wire `Template.parse`, update the ledger, and close the lexer ledger gap

**Files:**
- Modify: `src/main/java/se/alipsa/hfjinja/Template.java`
- Modify: `src/test/java/se/alipsa/hfjinja/PublicApiTest.java`
- Modify: `upstream/mapping.yml`
- Modify: `build.gradle` (`upstreamVerify`)

**Interfaces:**
- Produces: `Template.parse` retaining an immutable `Statement.Program`; `render` still throws `UnsupportedOperationException` until WP4.

- [ ] **Step 1: Write the failing test**

```java
  @Test
  void templateParseRejectsMalformedSourceAtThePublicBoundary() {
    var thrown = assertThrows(TemplateSyntaxException.class, () -> Template.parse("{% if x %}"));
    assertEquals(ErrorCategory.SYNTAX, thrown.category());
  }

  @Test
  void templateParseAcceptsAValidTemplateAndStillDefersRendering() {
    var template = Template.parse("Hello {{ name }}!");
    assertThrows(UnsupportedOperationException.class, () -> template.render(Map.of()));
  }
```

- [ ] **Step 2: Run to verify it fails.** Expected: FAIL — `Template.parse` currently accepts anything.
- [ ] **Step 3: Implement**

```java
  private final Statement.Program program;

  private Template(Statement.Program program) {
    this.program = program;
  }

  public static Template parse(String source, TemplateOptions options) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(options, "options");
    return new Template(Parser.parse(Lexer.tokenize(source, options), options));
  }
```

Keep the field for WP4 (annotate it `@SuppressWarnings("unused")` only if the build warns). Do **not** add a public accessor for it — `req/project-description.md:166-168` rules out exposing parsed state.

- [ ] **Step 4: Update `upstream/mapping.yml`**

`lexer.ts` is still `status: planned` with empty lists despite PR #6 landing the port. Correct all three entries:

```yaml
lexer.ts:
  milestone: M1
  status: implemented
  java: [src/main/java/se/alipsa/hfjinja/internal/lexer/Lexer.java, src/main/java/se/alipsa/hfjinja/internal/lexer/Token.java, src/main/java/se/alipsa/hfjinja/internal/lexer/TokenType.java]
  tests: [src/test/java/se/alipsa/hfjinja/internal/lexer/LexerTest.java]
ast.ts:
  milestone: M1
  status: implemented
  java: [src/main/java/se/alipsa/hfjinja/internal/ast/Statement.java, src/main/java/se/alipsa/hfjinja/internal/ast/Expression.java]
  tests: [src/test/java/se/alipsa/hfjinja/internal/ast/AstInventoryTest.java]
parser.ts:
  milestone: M1
  status: implemented
  java: [src/main/java/se/alipsa/hfjinja/internal/parser/Parser.java]
  tests: [src/test/java/se/alipsa/hfjinja/internal/parser/ParserTest.java, src/test/java/se/alipsa/hfjinja/internal/parser/AstSnapshotDifferentialTest.java]
```

Keep the inline flow-list form — `upstreamVerify` validates that shape by regex (`build.gradle`, the `entry.java ==~ /\[\s*\S(?:.*\S)?\s*\]/` check).

- [ ] **Step 5: Make the ledger gap detectable**

The `planned` status passed verification while a full port sat in the tree, because nothing cross-checks the filesystem. Add to `upstreamVerify`, inside the per-file mapping loop:

```groovy
      } else if (entry.status == 'implemented') {
        if (!(entry.java ==~ /\[\s*\S(?:.*\S)?\s*\]/)
            || !(entry.tests ==~ /\[\s*\S(?:.*\S)?\s*\]/)) {
          throw new GradleException("Implemented mapping must name Java and test coverage: ${basename}")
        }
        // Named paths must actually exist, or the ledger drifts silently.
        ((entry.java + entry.tests) =~ /(src\/[^,\]\s]+\.java)/).collect { it[1] }.each { declared ->
          if (!layout.projectDirectory.file(declared).asFile.isFile()) {
            throw new GradleException("Mapping entry names a missing file: ${basename} -> ${declared}")
          }
        }
      }
```

And after the loop, catch the inverse — a `planned` entry whose Java package already exists:

```groovy
    def plannedPackages = ['lexer.ts': 'internal/lexer', 'ast.ts': 'internal/ast', 'parser.ts': 'internal/parser',
                           'runtime.ts': 'internal/runtime', 'utils.ts': 'internal/util']
    mappingEntries.each { basename, entry ->
      def packagePath = plannedPackages[basename]
      if (entry.status == 'planned' && packagePath != null
          && layout.projectDirectory.dir("src/main/java/se/alipsa/hfjinja/${packagePath}").asFile.isDirectory()) {
        throw new GradleException(
            "Mapping entry is still 'planned' but its Java package exists: ${basename}")
      }
    }
```

- [ ] **Step 6: Verify the new check actually catches the old bug**

Mutate a copy and restore it directly. Do **not** use `git checkout` or `git stash` here: the ledger edits from Step 4 are uncommitted, so `git checkout` would discard them, and `git stash pop` would apply whatever unrelated stash happens to be on top.

```bash
cp upstream/mapping.yml /tmp/mapping.yml.bak
sed -i 's/^  status: implemented$/  status: planned/' upstream/mapping.yml
./gradlew upstreamVerify --offline    # expect: FAILS naming lexer.ts, ast.ts, parser.ts
cp /tmp/mapping.yml.bak upstream/mapping.yml && rm /tmp/mapping.yml.bak
./gradlew upstreamVerify --offline    # expect: BUILD SUCCESSFUL again
git diff --stat upstream/mapping.yml  # expect: only your Step 4 edits
```

Do not skip this step — a verifier that has never failed has not been tested.

- [ ] **Step 7: Run everything**

```bash
./gradlew clean build --offline
./gradlew check
./gradlew javadoc --offline
```

Expected: all BUILD SUCCESSFUL. Report the actual test count.

- [ ] **Step 8: Commit and open the PR**

```bash
git add -A
git commit -m "Wire Template.parse to the parser and refresh the upstream ledger"
gh pr create --title "Port the AST and parser (WP3 slice 2)" \
  --body-file docs/superpowers/plans/2026-08-20-wp3-ast-and-parser.md
```

Per `AGENTS.md`, this is one work-package-sized PR, ready for review (not draft).

---

## Gate G3 Evidence Checklist

Before opening the PR, confirm each of these and paste the real output into the PR body:

- [ ] `./gradlew check` passes, including `upstreamVerify`, `astSnapshotVerify`, and the Node corpus tasks.
- [ ] `AstInventoryTest` proves every `ast.ts` node has a Java counterpart.
- [ ] `AstSnapshotDifferentialTest` matches the pinned Node `parse` on every extracted `TEST_STRINGS` source (expected: 159) — state the actual fixture count.
- [ ] All four upstream "Parsing errors" fixtures produce `ErrorCategory.SYNTAX` with a location.
- [ ] Deep nesting produces `RESOURCE_LIMIT`, not `StackOverflowError`, **and** 200 nested parentheses still parse — the limit counts AST levels, not precedence dispatches.
- [ ] `javadoc -private -Xdoclint:all` reports zero errors.
- [ ] The termination property holds over 20,000 random token sequences — no hang, no raw JDK exception.
- [ ] `upstream/mapping.yml` marks `lexer.ts`, `ast.ts`, and `parser.ts` implemented, and the new verifier check was demonstrated to fail when they are not.
- [ ] `module-info.java` is unchanged.
- [ ] No production dependency was added — `build.gradle`'s `dependencies` block still contains only test entries.

## Deliberately Out of Scope

- Interpretation of any node (WP4). `Template.render` keeps throwing `UnsupportedOperationException`.
- Adding parser error patterns to `tools/corpus/error-patterns-0.5.9.json`. That file currently holds exactly one pattern; filling it out is WP1b work behind gate G1, and G3 does not depend on it.
- Removing AST allowlist exemptions. `upstream/ast-allowlist.json` values are milestone markers for *full* support; WP5 step 5 clears them. Parser coverage alone does not.
- Remapping `SourceLocation` back through `trim_blocks`/`lstrip_blocks` rewrites — a known, documented v1 limitation (`Lexer.java:16-22`), and the flipped defaults in Task 1 make it apply by default. Worth raising with the user as a WP4 question; it is not a G3 blocker.
