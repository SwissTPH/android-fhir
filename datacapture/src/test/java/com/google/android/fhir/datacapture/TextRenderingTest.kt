/*
 * Copyright 2026 Google LLC
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

package com.google.android.fhir.datacapture

import android.app.Application
import android.graphics.Typeface
import android.os.Build
import android.text.Spanned
import android.text.style.BulletSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.datacapture.extensions.displayStringSpanned
import com.google.android.fhir.datacapture.extensions.localizedFlyoverSpanned
import com.google.android.fhir.datacapture.extensions.localizedHelpSpanned
import com.google.android.fhir.datacapture.extensions.localizedPrefixSpanned
import com.google.android.fhir.datacapture.testing.DataCaptureTestApplication
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.MarkdownType
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.StringType
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Formatted text (`rendering-xhtml` / `rendering-markdown`, static or with their own
 * `cqf-expression`) and plain text with `<`, per fixture in `sampledata/text_rendering/`. What the
 * user sees is compared with `text_rendering/expected.txt`; the test prints the actual lines with a
 * `TEXTRENDER` prefix. See FHIRCORE-TEXT-FIX-REPORT.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], application = DataCaptureTestApplication::class)
class TextRenderingTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val context = ApplicationProvider.getApplicationContext<Application>()
  private val parser = FhirContext.forR4Cached().newJsonParser()
  private val defaultLocale = Locale.getDefault()

  @Before
  fun setUp() {
    questionnaireViewModelCoroutineContext = mainDispatcherRule.testDispatcher
    ReflectionHelpers.setStaticField(DataCapture::class.java, "configuration", null)
  }

  @After
  fun tearDown() {
    Locale.setDefault(defaultLocale)
  }

  /**
   * Per fixture: each item's question text, prefix, flyover, help and answer options, then the
   * `QuestionnaireResponse.item.text` values; before and after answering "Yes" when the fixture has
   * the `demo_is_happy` question.
   */
  @Test
  fun shownText_perFixture() = runTest {
    val actual = mutableListOf<String>()
    for ((name, locale) in RUNS) {
      Locale.setDefault(locale)
      val label = if (locale == Locale.ENGLISH) name else "$name[${locale.language}]"
      val viewModel = viewModel(name)
      viewModel.runViewModelBlocking {
        actual += viewModel.shown().map { "$label before $it" }
        if (viewModel.questions().any { it.questionnaireItem.linkId == HAPPY }) {
          viewModel
            .questions()
            .first { it.questionnaireItem.linkId == HAPPY }
            .setAnswer(
              QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent()
                .setValue(BooleanType(true)),
            )
          // the state is recomputed on Dispatchers.IO after an answer
          withContext(Dispatchers.Default) {
            withTimeout(10_000) {
              viewModel.questionnaireStateStateFlow.first { state ->
                state.items.any {
                  (it as? QuestionnaireAdapterItem.Question)?.item?.let { q ->
                    q.questionnaireItem.linkId == HAPPY && q.answers.isNotEmpty()
                  } ?: false
                }
              }
            }
          }
          actual += viewModel.shown().map { "$label after $it" }
        }
        actual +=
          viewModel.getQuestionnaireResponse().item.flatMap { it.responseTexts() }.map {
            "$label response $it"
          }
      }
    }
    actual.forEach { println("TEXTRENDER $it") }
    val expected =
      javaClass
        .getResourceAsStream("/text_rendering/expected.txt")!!
        .bufferedReader()
        .readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
    assertThat(actual.joinToString("\n")).isEqualTo(expected.joinToString("\n"))
  }

  /** HAPI R4 keeps an extension on an extension's value (`_valueMarkdown`, `_valueString`). */
  @Test
  fun hapiModel_keepsExtensionOnExtensionValue() {
    for (name in listOf("b_proposal_markdown", "c_proposal_xhtml")) {
      val q = parser.parseResource(Questionnaire::class.java, fixture(name))
      val rendering = q.item.first().item.last().textElement.extension.first()
      val value = rendering.value
      if (name[0] == 'b') {
        assertThat(value).isInstanceOf(MarkdownType::class.java)
      } else {
        assertThat(value).isInstanceOf(StringType::class.java)
      }
      assertThat(value.extension.map { it.url }).containsExactly(CQF)
      val back = parser.encodeResourceToString(q)
      assertThat(back).contains(if (name[0] == 'b') "\"_valueMarkdown\"" else "\"_valueString\"")
    }
  }

  private fun QuestionnaireViewModel.questions() =
    questionnaireStateStateFlow.value.items.mapNotNull {
      (it as? QuestionnaireAdapterItem.Question)?.item
    }

  private fun QuestionnaireViewModel.shown(): List<String> =
    questions().flatMap { item ->
      val q = item.questionnaireItem
      buildList {
        if (q.linkId != HAPPY) add("${q.linkId} text ${item.questionText?.let(::describe)}")
        q.localizedPrefixSpanned?.let { add("${q.linkId} prefix ${describe(it)}") }
        item.enabledDisplayItems.localizedFlyoverSpanned?.let {
          add("${q.linkId} flyover ${describe(it)}")
        }
        q.localizedHelpSpanned?.let { add("${q.linkId} help ${describe(it)}") }
        q.answerOption.forEachIndexed { i, option ->
          add("${q.linkId} option$i ${describe(option.value.displayStringSpanned(context))}")
        }
      }
    }

  private fun QuestionnaireResponse.QuestionnaireResponseItemComponent.responseTexts():
    List<String> =
    listOf("$linkId ${text?.let { "\"${it.replace("\n", "\\n")}\"" }}") + item.flatMap { it.responseTexts() }

  private fun fixture(name: String) =
    javaClass.getResourceAsStream("/text_rendering/$name.json")!!.bufferedReader().readText()

  private fun viewModel(name: String): QuestionnaireViewModel {
    val state = SavedStateHandle()
    state[QuestionnaireFragment.EXTRA_QUESTIONNAIRE_JSON_STRING] = fixture(name)
    return QuestionnaireViewModel(context, state)
  }

  private suspend inline fun QuestionnaireViewModel.runViewModelBlocking(
    crossinline block: suspend () -> Unit,
  ) {
    val collectJob =
      viewModelScope.launch(mainDispatcherRule.testDispatcher) {
        questionnaireStateStateFlow.collect()
      }
    block.invoke()
    collectJob.cancel()
  }

  companion object {
    const val CQF = "http://hl7.org/fhir/StructureDefinition/cqf-expression"
    const val HAPPY = "demo_is_happy"

    val RUNS =
      listOf(
          "a_tricc_markdown",
          "a_tricc_html",
          "b_proposal_markdown",
          "c_proposal_xhtml",
          "d_escape_markdown",
          "d_escape_xhtml",
          "e_static_markdown",
          "e_static_xhtml",
          "f_html_in_text",
          "g_case10_static_xhtml_live_markdown",
          "h_lt_plain",
          "i_help_flyover",
          "j_answer_options",
          "k_translation",
        )
        .map { it to Locale.ENGLISH } + ("k_translation" to Locale.FRENCH)

    /** `text` plus each span: `[s,e)bold|italic|underline|bullet|<class>`. */
    fun describe(s: Spanned): String = buildString {
      append('"').append(s.toString().replace("\n", "\\n")).append('"')
      for (span in s.getSpans(0, s.length, Any::class.java)) {
        val tag =
          when {
            span is StyleSpan && span.style == Typeface.BOLD -> "bold"
            span is StyleSpan && span.style == Typeface.ITALIC -> "italic"
            span is UnderlineSpan -> "underline"
            span is BulletSpan -> "bullet"
            else -> span.javaClass.simpleName
          }
        append(" [${s.getSpanStart(span)},${s.getSpanEnd(span)})$tag")
      }
    }
  }
}
