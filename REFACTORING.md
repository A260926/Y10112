# Token cursor refactoring

## Goal

The full (non-streaming) and streaming parse loops used to keep parallel
control flow for token advancement, EOF detection, current position and the
`PathMatcher` path computation. That state is now owned by a single internal
token cursor, without changing the public `Parser` factories
(`Parser.default(...)` / the deprecated `Parser.invoke(...)`) or any output
types.

## State ownership

- **`Lexer`** (public, unchanged role): pure tokenizer. It owns character
  buffering and the string / number / container-boundary tokenization shared
  by both parse modes. It also no longer drops a trailing value token whose
  first character is the last character of the input (previously `parse("5")`
  or `{"a":1} x` silently discarded the last token, which let extra root
  values slip through in full mode).
- **`TokenCursor`** (`internal`, new, `TokenCursor.kt`): the single owner of
  - *lookahead*: `peek()` / `next()` are the only ways the parse loops read
    tokens; peeking never advances the reported position;
  - *position*: `index` / `line`, mirrored from the lexer after every
    consumed token;
  - *EOF semantics*: `exhausted` is sticky — once an `EOF` token is observed
    the cursor stays exhausted, and the full parse loop terminates on it;
  - *path stack*: exposes the `PathTracker` used for `World.path`.
- **`PathTracker`** (`internal`, `TokenCursor.kt`): owns the stack of path
  segments behind `World.path`. `World.pushValue`/`popValue` are the only
  mutators and always pair `push`/`pop` — on both the hit and the miss
  branches of a `PathMatcher` — so the stack stays balanced even while
  unwinding nested containers.
- **`World`** (public, signature-compatible): still owns the status/value
  stacks driven by the `StateMachine`, but delegates `path` to the cursor's
  `PathTracker` instead of recomputing it from its own stack.
- **`KlaxonParser`** (`internal`): the two loops now differ only in their
  termination condition. Full mode consumes exactly one root value and then
  only whitespace up to EOF (any other token is rejected by the state
  machine); streaming mode stops as soon as a value completes and leaves the
  next token for the caller (including the leading-comma skip between
  streamed values).

## Lifetime and isolation

A `TokenCursor`, its `PathTracker` and the `World` are all created per
`parse()` call. If an exception escapes a deeply nested parse, the whole
cursor is discarded with it, so the next independent parser can never observe
a stale path or position. Concurrent parses through different `Parser`
instances share nothing (a `passedLexer` is still shared with its `JsonReader`
by design, exactly as before). Reader/InputStream closing responsibilities are
unchanged: `parse(StringBuilder)` closes its internal reader, while
`parse(Reader)` / `parse(InputStream)` never close the caller's stream.

## Guards

`CursorGuardTest` pins the observable contract: full/streaming agreement on
the same value, rejection of consecutive root values in full mode, streaming
hand-off of the next token, `PathMatcher` push/pop pairing on hit and miss
branches, a clean path for a fresh parser after a deep error, chunked
readers/streams (UTF-8 across buffer boundaries, surrogate pairs, CRLF line
counting, very long numbers, nested empty containers) and EOF boundaries.
