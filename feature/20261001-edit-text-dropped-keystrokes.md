# Edit-text questions drop keystrokes before committing the answer

| Field | Value |
|-------|-------|
| **Status** | Implemented |
| **Repos** | `android-fhir` (`datacapture/`), reported from openSRP FHIRCore Android (`android/`) |
| **Branch** | `perf/questionnaire-expression-evaluation`, off `release-22.07.2026` |
| **Scope** | One behavioural fix in `EditTextFieldState`. No API change, no change to any other widget. |

Valid status values: `Draft` → `Approved` → `Implemented` → `Superseded`.

---

## Part I — Business spec

### 1. Problem

Typing into a numeric question whose value feeds another question's
`calculatedExpression` produced a result computed from a **prefix** of what was typed.

Reported case: a height question of `180` cm driving a BMI `calculatedExpression`. BMI came out as
if height were `18`. The correct BMI only appeared once the height field lost focus.

The same mechanism affects any `decimal`, `integer`, single-line `string` or multi-line `text`
question — anything rendered through `EditTextViewHolderDelegate`. Whether it is *visible* depends
on whether something else reads the answer while the field still has focus, so
`calculatedExpression`, `enableWhenExpression` and validation are where it shows up. A form whose
fields are independent looks fine, which is why this survived so long.

### 2. Expected vs actual

| | Expected | Actual (before the fix) |
|---|---|---|
| Type `180`, pause, read the answer | `180` | `18`, or nothing at all |
| Type `180`, pause, read a dependent `calculatedExpression` | computed from `180` | computed from `18` |
| Type `180`, move focus away | `180` | `180` (focus-loss sync papered over it) |

### 3. Out of scope

- The `remember(questionnaireViewItem)` rebuild discussed in §6 — it is wasteful and leaks an idle
  collector per commit, but it is not what loses the keystroke. Tracked separately.
- Any consumer-side workaround. An earlier attempt in the openSRP app replaced the four SDK
  edit-text factories with debounced copies; that is superseded by this fix and was reverted,
  because duplicating `EditTextViewHolderDelegate`'s rendering drifts on every SDK bump and it
  could not cover questions with an item control, answer options, an answer value set or an
  `answerExpression`.

---

## Part II — Technical specification

### 4. Root cause

`EditTextFieldState` (`datacapture/.../views/compose/EditTextFieldItem.kt`) commits the field text
from a `snapshotFlow`:

```kotlin
init {
  coroutineScope.launch {
    snapshotFlow { inputText }
      .drop(1) // Drops the initial value emitted by snapshotFlow
      .debounce(HANDLE_INPUT_DEBOUNCE_TIME)
      .collectLatest { handleTextInputChange(it) }
  }
}
```

`snapshotFlow` emits whatever `inputText` holds **when collection starts**, so `drop(1)` is only
"the initial value" if nothing was typed before then. It is not a safe assumption:
`coroutineScope.launch` is dispatched, so the collector starts on a later main-thread turn, and a
keystroke landing in between makes the typed text the first emission. `drop(1)` discards it.

Nothing emits afterwards if the user has stopped typing, so the answer stays behind until
`onFocusChanged` re-syncs it on blur — matching the reported "only correct after leaving the field".

### 5. Why it reproduces so easily while typing

The state is rebuilt on every commit:

```kotlin
remember(questionnaireViewItem) { EditTextFieldState(...) }   // EditTextViewHolderDelegate
```

`handleTextInputChange` sets the answer → a new `QuestionnaireViewItem` is delivered → `remember`'s
key changes → a **new** `EditTextFieldState` with a **new** collector, re-opening the window. So the
race is not a one-off at first render; it reopens after every debounced commit, which is exactly
once per ~500 ms of continuous typing:

```
type "1", "8"            → inputText = "18"
debounce fires           → commit "18" → new view item → state rebuilt, collector C2 queued
type "0"                 → inputText = "180"   (before C2 starts collecting)
C2 starts                → first emission "180" → drop(1) discards it
user stops typing        → nothing more emitted → answer stays "18"
```

### 6. Fix

Match the value instead of the position:

```kotlin
snapshotFlow { inputText }
  .dropWhile { it == initialInputText }
  .debounce(HANDLE_INPUT_DEBOUNCE_TIME)
  .collectLatest { handleTextInputChange(it) }
```

`dropWhile` skips the *leading run* of emissions that still equal what the field opened with, which
is what `drop(1)` was reaching for. Properties that follow:

- Nothing typed → the only emission equals `initialInputText` → skipped, as before.
- Typed before the collector started → the first emission differs → committed.
- Edited and then undone back to the opening value → `dropWhile` has already stopped dropping, so
  the undo is committed rather than being mistaken for the initial value.
- Clearing a field that opened with a value → `""` differs → committed.

Only `init` changes; `inputText`, `onInputTextChange` and the constructor are untouched, so
`EditTextFieldItem`, `OutlinedEditTextFieldItem` and `EditTextViewHolderDelegate` are unchanged.

### 7. Tests

`datacapture/src/test/java/com/google/android/fhir/datacapture/views/compose/EditTextFieldStateTest.kt`.

The two tests that fail without the fix:

- `commits a keystroke that lands before the collector starts`
- `commits a keystroke typed after a previous commit rebuilt the state`

The rest pin behaviour that must not regress: the opening value is not committed, clearing is
committed, an edit-and-undo is committed, commits wait for a typing pause, and every change after
the first is committed.

Two harness details are load-bearing:

- The state runs on a `CoroutineScope(StandardTestDispatcher(testScheduler))` the test owns, **not**
  on `backgroundScope`. The `launch` has to stay queued until the test advances the scheduler;
  that is what makes "typed before the collector started" reproducible. (`backgroundScope` work was
  not run by `advanceUntilIdle()` here, and `UnconfinedTestDispatcher` starts the collector eagerly,
  which hides the bug.)
- `Snapshot.sendApplyNotifications()` after each write, or `snapshotFlow` never observes it.

### 8. Acceptance

1. Typing `180` into a question that feeds a `calculatedExpression` yields a value computed from
   `180` without leaving the field.
2. A question left untouched does not commit an answer just by being rendered.
3. Clearing a populated field clears the answer.
4. No change to `decimal`/`integer`/`string`/`text` rendering, validation or units.

### 9. Follow-up

`remember(questionnaireViewItem)` in `EditTextViewHolderDelegate` rebuilds `EditTextFieldState` —
and launches another collector on the same `rememberCoroutineScope` — on every commit. The old
collectors are never cancelled and never emit again, so this is an idle-coroutine leak proportional
to keystrokes rather than a correctness bug, and the fix above does not depend on it. Addressing it
means keying the `remember` on a stable question identity and keeping `handleTextInputChange` fresh
via `rememberUpdatedState`, so a stale `QuestionnaireViewItem` is not captured.