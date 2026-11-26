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

package com.google.android.fhir.datacapture.extensions

import android.os.Build
import com.google.android.fhir.datacapture.TextRenderingTest.Companion.describe
import com.google.common.truth.Truth.assertThat
import org.hl7.fhir.r4.model.Expression
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.MarkdownType
import org.hl7.fhir.r4.model.StringType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class MoreTextRenderingTest {

  private fun md(v: String) = describe(DisplayText.Formatted(v.markdownToHtml()).toSpanned())

  private fun xhtml(v: String) = describe(DisplayText.Formatted(v).toSpanned())

  @Test
  fun markdown_marksListsEscapes() {
    assertThat(md("a **bold** and *italic* and _em_ text"))
      .isEqualTo("\"a bold and italic and em text\" [2,6)bold [11,17)italic [22,24)italic")
    assertThat(md("# Title\n\n- one\n* two\n\n1. three\n2. four"))
      .isEqualTo("\"Title\\none\\ntwo\\n1. three\\n2. four\" [0,5)bold [6,10)bullet [10,14)bullet")
    assertThat(md("x *a\\*b \\<c\\> \\& \"d\"*")).isEqualTo("\"x a*b <c> & \"d\"\" [2,15)italic")
    assertThat(md("weight_for_age")).isEqualTo("\"weight_for_age\"")
    assertThat(md("Age **<5** years")).isEqualTo("\"Age <5 years\" [4,6)bold")
    assertThat(md("line one\nline two\n\nnext paragraph"))
      .isEqualTo("\"line one\\nline two\\nnext paragraph\"")
  }

  @Test
  fun markdown_unescapedValueBreaksTheMark() {
    assertThat(md("x *a*b <c> & \"d\"*")).isEqualTo("\"x ab <c> & \"d\"*\" [2,3)italic")
  }

  /** What Android's `Html` (FROM_HTML_MODE_COMPACT) does with the XHTML subset. */
  @Test
  fun xhtml_androidHtmlSubset() {
    assertThat(
        xhtml("<div xmlns=\"http://www.w3.org/1999/xhtml\"><p>We are <strong>…</strong></p></div>"),
      )
      .isEqualTo("\"We are …\" [7,8)bold")
    // <ol> items are bullets: the numbers are lost
    assertThat(xhtml("<ol><li>x</li><li>y</li></ol>"))
      .isEqualTo("\"x\\ny\" [0,2)bullet [2,3)bullet")
    assertThat(xhtml("<table><tr><td>a</td><td>b</td></tr></table>")).isEqualTo("\"ab\"")
  }

  /** Plain text: as typed with a rendering extension, the legacy HTML parse without one. */
  @Test
  fun plainText_staysPlainOnlyWithARendering() {
    val withRendering = StringType("x").apply { addExtension(xhtmlExt("<b>x</b>")) }
    val legacy = StringType("x")
    for (label in listOf("Age <5 years", "MUAC<115mm", "<2 months: give 1/2 tablet", "a\nb &lt;")) {
      assertThat(withRendering.plainDisplayText(label).toSpanned().toString()).isEqualTo(label)
      assertThat(withRendering.plainDisplayText(label).toAnnotatedString().text).isEqualTo(label)
    }
    assertThat(legacy.plainDisplayText("Age <5 years").toSpanned().toString()).isEqualTo("Age ")
    assertThat(legacy.plainDisplayText("MUAC<115mm").toSpanned().toString()).isEqualTo("MUAC")
    assertThat(legacy.plainDisplayText("<2 months").toSpanned().toString()).isEqualTo("")
    assertThat(legacy.plainDisplayText("Age < 5 years").toSpanned().toString())
      .isEqualTo("Age < 5 years")
    assertThat(legacy.plainDisplayText("Age &lt;5 years").toSpanned().toString())
      .isEqualTo("Age <5 years")
    // QuestionnaireResponse.item.text
    assertThat(
        StringType("Age <5 years")
          .apply { addExtension(xhtmlExt("<b>x</b>")) }
          .localizedPlainText(),
      )
      .isEqualTo("Age <5 years")
    assertThat(StringType("Age <5 years").localizedPlainText()).isEqualTo("Age ")
  }

  @Test
  fun textRenderings_xhtmlFirst() {
    val text =
      StringType("plain").apply {
        addExtension(mdExt("**md**"))
        addExtension(xhtmlExt("<b>x</b>"))
      }
    assertThat(text.textRenderings.map { it.javaClass.simpleName })
      .containsExactly("Xhtml", "Markdown")
      .inOrder()
  }

  /** The use cases of ohs-foundation/kotlin-fhir-data-capture#87. */
  @Test
  fun resolveDisplayText_useCases() {
    val live = mapOf("plain" to "live plain", "xhtml" to "<b>live</b>", "md" to "**live md**")
    fun resolve(text: StringType) =
      text.resolveDisplayText(
        livePlain = if (text.cqfExpression != null) live["plain"] else null,
        evaluate = { live[it.expression] },
        lang = "en",
      )
    fun element(plainLive: Boolean, xhtml: Boolean?, md: Boolean?) =
      StringType("static plain").apply {
        // null: absent; false: static; true: live
        xhtml?.let { addExtension(xhtmlExt("<i>static</i>", if (it) "xhtml" else null)) }
        md?.let { addExtension(mdExt("*static md*", if (it) "md" else null)) }
        if (plainLive) addExtension(cqf("plain"))
      }
    val plain = DisplayText.LegacyHtml("static plain")
    // 1-4: plain static
    assertThat(resolve(element(false, null, null))).isEqualTo(plain)
    assertThat(resolve(element(false, false, null)))
      .isEqualTo(DisplayText.Formatted("<i>static</i>"))
    assertThat(resolve(element(false, null, false)))
      .isEqualTo(DisplayText.Formatted("<p><i>static md</i></p>"))
    assertThat(resolve(element(false, false, false)))
      .isEqualTo(DisplayText.Formatted("<i>static</i>"))
    // 5-8: plain live, live renderings
    assertThat(resolve(element(true, null, null))).isEqualTo(DisplayText.LegacyHtml("live plain"))
    assertThat(resolve(element(true, true, null))).isEqualTo(DisplayText.Formatted("<b>live</b>"))
    assertThat(resolve(element(true, null, true)))
      .isEqualTo(DisplayText.Formatted("<p><b>live md</b></p>"))
    assertThat(resolve(element(true, true, true))).isEqualTo(DisplayText.Formatted("<b>live</b>"))
    // 9: a static rendering never hides live plain text (shown as typed)
    assertThat(resolve(element(true, false, null))).isEqualTo(DisplayText.Plain("live plain"))
    assertThat(resolve(element(true, false, false))).isEqualTo(DisplayText.Plain("live plain"))
    // 10: the first LIVE rendering
    assertThat(resolve(element(true, false, true)))
      .isEqualTo(DisplayText.Formatted("<p><b>live md</b></p>"))
    // a live rendering that evaluates to nothing falls back to the live plain value
    assertThat(
        element(true, true, null).resolveDisplayText("live plain", { null }, "en"),
      )
      .isEqualTo(DisplayText.Plain("live plain"))
    // plain static, live rendering: evaluated when an evaluator is given, else its static value
    assertThat(resolve(element(false, true, null))).isEqualTo(DisplayText.Formatted("<b>live</b>"))
    assertThat(element(false, true, null).resolveDisplayText(lang = "en"))
      .isEqualTo(DisplayText.Formatted("<i>static</i>"))
  }

  @Test
  fun resolveDisplayText_translations() {
    val text =
      StringType("Hello <friend>").apply {
        addExtension(translation("fr", "Bonjour <ami>"))
        addExtension(xhtmlExt("<b>Hello</b>"))
      }
    assertThat(text.resolveDisplayText(lang = "en"))
      .isEqualTo(DisplayText.Formatted("<b>Hello</b>"))
    // untranslated rendering skipped; translated plain shown as typed
    assertThat(text.resolveDisplayText(lang = "fr")).isEqualTo(DisplayText.Plain("Bonjour <ami>"))
    assertThat(text.resolveDisplayText(lang = "fr-CH"))
      .isEqualTo(DisplayText.Plain("Bonjour <ami>"))
    text.addExtension(
      mdExt("**Hello**").apply { value.addExtension(translation("fr", "**Bonjour**")) },
    )
    assertThat(text.resolveDisplayText(lang = "fr"))
      .isEqualTo(DisplayText.Formatted("<p><b>Bonjour</b></p>"))
  }

  private fun cqf(expression: String) =
    Extension(
      "http://hl7.org/fhir/StructureDefinition/cqf-expression",
      Expression().setLanguage("text/fhirpath").setExpression(expression),
    )

  private fun xhtmlExt(value: String, live: String? = null) =
    Extension(
      EXTENSION_RENDERING_XHTML_URL,
      StringType(value).apply { live?.let { addExtension(cqf(it)) } },
    )

  private fun mdExt(value: String, live: String? = null) =
    Extension(
      EXTENSION_RENDERING_MARKDOWN_URL,
      MarkdownType(value).apply { live?.let { addExtension(cqf(it)) } },
    )

  private fun translation(lang: String, content: String) =
    Extension("http://hl7.org/fhir/StructureDefinition/translation").apply {
      addExtension("lang", org.hl7.fhir.r4.model.CodeType(lang))
      addExtension("content", StringType(content))
    }
}
