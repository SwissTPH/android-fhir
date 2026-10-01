/*
 * Copyright 2025-2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir.datacapture.views.compose

import android.os.Build
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.snapshots.Snapshot
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for [EditTextFieldState]'s commit pipeline.
 *
 * The state used to skip the value `snapshotFlow` emits first *by position* (`drop(1)`). Its
 * collector is started from a dispatched `launch`, so a keystroke can land before collection
 * begins - that keystroke is then the first emission, and it was thrown away. The answer only
 * caught up on focus loss, so typing `180` into a height question left a BMI `calculatedExpression`
 * computing from `18`.
 *
 * [Field] deliberately runs the state on its own [StandardTestDispatcher] rather than on
 * `backgroundScope`: the launch has to stay queued until the test advances the scheduler, which is
 * what makes "typed before the collector started" reproducible.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class EditTextFieldStateTest {

  private class Field(scope: CoroutineScope, initialInputText: String) {
    val committed = mutableListOf<String>()
    val state =
      EditTextFieldState(
        hint = null,
        helperText = null,
        isError = false,
        isReadOnly = false,
        keyboardOptions = KeyboardOptions(),
        isMultiLine = false,
        initialInputText = initialInputText,
        handleTextInputChange = { committed += it },
        coroutineScope = scope,
      )

    /** `snapshotFlow` only observes a state write once the global snapshot is applied. */
    fun type(text: String) {
      state.onInputTextChange(text)
      Snapshot.sendApplyNotifications()
    }
  }

  private fun TestScope.field(initialInputText: String, block: Field.() -> Unit) {
    val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
    try {
      Field(scope, initialInputText).block()
    } finally {
      scope.cancel()
    }
  }

  @Test
  fun `commits a keystroke that lands before the collector starts`() = runTest {
    field(initialInputText = "") {
      // No advance first: the collector inside `init` is still queued, so this keystroke is the
      // value snapshotFlow emits first - the one `drop(1)` used to discard.
      type("180")
      advanceUntilIdle()

      assertThat(committed).containsExactly("180")
    }
  }

  @Test
  fun `commits a keystroke typed after a previous commit rebuilt the state`() = runTest {
    // Committing replaces the QuestionnaireViewItem, which rebuilds this state with the value
    // just committed and starts a fresh collector. Typing into it before that collector runs is
    // the sequence that produced a BMI computed from a half-typed height.
    field(initialInputText = "18") {
      type("180")
      advanceUntilIdle()

      assertThat(committed).containsExactly("180")
    }
  }

  @Test
  fun `does not commit the value the field opened with`() = runTest {
    field(initialInputText = "180") {
      advanceUntilIdle()

      assertThat(committed).isEmpty()
    }
  }

  @Test
  fun `clearing a field that opened with a value is committed`() = runTest {
    field(initialInputText = "180") {
      advanceUntilIdle()

      type("")
      advanceUntilIdle()

      assertThat(committed).containsExactly("")
    }
  }

  @Test
  fun `commits a value edited back to what the field opened with`() = runTest {
    // Only the *leading* run of unchanged values is skipped, so an edit-and-undo still reaches
    // the answer rather than being mistaken for the initial value.
    field(initialInputText = "180") {
      advanceUntilIdle()

      type("18")
      advanceUntilIdle()
      type("180")
      advanceUntilIdle()

      assertThat(committed).containsExactly("18", "180").inOrder()
    }
  }

  @Test
  fun `commits only once typing pauses`() = runTest {
    field(initialInputText = "") {
      advanceUntilIdle()

      type("1")
      advanceTimeBy(HANDLE_INPUT_DEBOUNCE_TIME / 2)
      type("18")
      advanceTimeBy(HANDLE_INPUT_DEBOUNCE_TIME / 2)
      type("180")
      assertThat(committed).isEmpty()

      advanceUntilIdle()

      assertThat(committed).containsExactly("180")
    }
  }

  @Test
  fun `keeps committing every change after the first`() = runTest {
    field(initialInputText = "") {
      advanceUntilIdle()

      type("1")
      advanceUntilIdle()
      type("12")
      advanceUntilIdle()

      assertThat(committed).containsExactly("1", "12").inOrder()
    }
  }

  @Test
  fun `exposes the text it was built with until something is typed`() = runTest {
    field(initialInputText = "180") {
      assertThat(state.inputText).isEqualTo("180")

      type("181")

      assertThat(state.inputText).isEqualTo("181")
    }
  }
}
