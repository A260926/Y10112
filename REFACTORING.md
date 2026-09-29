# Parser cursor refactoring

## Goal

Streaming and full parses used to be driven by two separate loops (`partialParseLoop`
and `fullParseLoop`) with parallel control flow for token advancement, EOF detection,
the current position and path bookkeeping. Both now share a single loop and a single
owner of that mutable state: the internal `TokenCursor`.

The public surface is unchanged:

- `Parser.default(...)` / the deprecated `Parser(...)` factory keep the same signature.
- Return types are unchanged (`JsonObject` / `JsonArray` / scalar values, `JsonBase`
  in streaming mode).
- `Lexer`, `JsonReader`, `PathMatcher`, `World`-related extension points and the
  plugin extension functions on `Parser.Companion` still work the same way.

## State ownership

| State | Before | After |
| --- | --- | --- |
| One-token lookahead | `Lexer.peeked` | unchanged in `Lexer`; accessed through `TokenCursor.peek()/next()` |
| Character index / line | `Lexer.index` / `Lexer.line`, copied onto `World` per token in full mode only | exposed read-only via `TokenCursor.index` / `TokenCursor.line`; `StateMachine` reads them from the cursor when building error messages |
| Path | derived on demand from `World`'s value stack; matchers fired from `World.foundValue()` | explicit, balanced frame stack owned by `TokenCursor` (`ObjectFrame` / `ArrayFrame`); matchers are fired directly from the cursor when a primitive object value lands |
| EOF policy | different loop predicates in `partialParseLoop` / `fullParseLoop` | `TokenCursor.consumeRootValue(token, nestedBeforeToken)` |
| Value/status stack | `World` | unchanged, still in `World` (now `internal`) |

`TokenCursor` is instantiated once per `parse()` call. It never escapes the invocation,
so:

- a deep exception (syntax error or a matcher throwing) cannot leave a stale path for
  the next parser — its cursor is simply discarded;
- parsers running concurrently on different `Parser` instances have fully isolated
  cursors. In streaming mode several parses may legitimately share one `Lexer`
  (created by `JsonReader`), but the path stack, nesting counters and EOF policy still
  live in each parse's own cursor.

## The shared loop

`KlaxonParser.parseLoop` is the only loop:

1. peek the upcoming token and record `nestedBefore(token)` (a closing bracket is
   considered nested while a frame still exists for it to pop);
2. advance the cursor, which updates frames/path and fires path matchers;
3. run the token through the `StateMachine`;
4. continue while `consumeRootValue(...)` returns true.

- **Full mode** consumes exactly one root value and then only the `EOF` token
  (i.e. trailing whitespace). A second root value now correctly produces the
  "Expected EOF" error.
- **Streaming mode** keeps consuming while nested and stops as soon as the root
  container closes or EOF is reached. The token after the value stays buffered in the
  shared `Lexer`'s one-token lookahead for the caller. Nothing is read eagerly: input
  is still streamed token by token, and there is no "read all then re-parse" path.

## Path stack balance

Every `LEFT_BRACE`/`LEFT_BRACKET` pushes one frame and every
`RIGHT_BRACE`/`RIGHT_BRACKET` pops one frame, regardless of whether a `PathMatcher`
matches or not. Object keys are identified with lookahead (a `Value` followed by
`COLON`) and held as `pendingKey` until the value token arrives; array frames track
the current element index. Empty objects/arrays push and pop without firing any
matcher.

## Lexer EOF fix

`Lexer.actualNextToken()` previously conflated "the final character was read" with
EOF: when the last physical character was whitespace (or a value was followed by
whitespace and another token), the trailing content could silently disappear
(e.g. `"1 2"`, `"\"x\"x"`, `"{} true"` parsed as if the rest did not exist). The
whitespace skip now distinguishes *trailing whitespace* (EOF follows) from
*whitespace before more input*, so full parsing really only accepts whitespace after
the unique root value. The fix is inside the tokenizer; error positions (character
index and CRLF line counting) are unchanged.

## Resource and encoding conventions

- `parse(StringBuilder)` still wraps the input in a `StringReader` and closes it.
- `parse(InputStream, Charset)` still converts to a reader without taking ownership
  of the stream; `parse(fileName)` still closes its `FileInputStream`.
- `parse(JsonReader)` streaming still reuses `jsonReader.lexer` and does not close
  the underlying reader.
- UTF-8 decoding still happens in `InputStreamReader`; multibyte sequences crossing
  buffer boundaries, surrogate pairs in strings and CRLF line numbering are covered
  by `CursorGuardTest` (chunked reader/stream cases).

## Verification

- `./gradlew test --tests '*CursorGuardTest' --info` prints, for
  `cursorExposesPositionAndPath`, each token together with its character index, line
  and current path (e.g. `10@12:2:$.a[0]`).
- `./gradlew test` runs the full TestNG suite.
