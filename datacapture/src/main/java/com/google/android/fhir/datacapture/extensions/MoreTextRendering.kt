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

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannedString
import androidx.compose.ui.text.AnnotatedString
import com.google.android.fhir.getLocalizedText
import java.util.Locale
import org.hl7.fhir.r4.model.Expression
import org.hl7.fhir.r4.model.PrimitiveType
import org.hl7.fhir.r4.model.StringType

internal const val EXTENSION_RENDERING_XHTML_URL =
  "http://hl7.org/fhir/StructureDefinition/rendering-xhtml"
internal const val EXTENSION_RENDERING_MARKDOWN_URL =
  "http://hl7.org/fhir/StructureDefinition/rendering-markdown"

/**
 * A formatted alternative of a string element, read from its `rendering-xhtml` or
 * `rendering-markdown` extension.
 */
internal sealed class TextRendering(
  /** The extension's value. It carries its own `cqf-expression` when the formatted text is live. */
  val valueElement: PrimitiveType<*>,
) {
  val cqfExpression: Expression?
    get() = valueElement.cqfExpression

  /** The static value, translated when the extension's value carries a translation for [lang]. */
  fun staticValue(lang: String): String? =
    (valueElement as? StringType)?.getLocalizedText(lang) ?: valueElement.primitiveValue()

  fun hasTranslation(lang: String): Boolean = (valueElement as? StringType).hasTranslation(lang)

  /** The value as the HTML subset Android's `Html` understands. */
  abstract fun toHtml(value: String): String

  class Xhtml(valueElement: PrimitiveType<*>) : TextRendering(valueElement) {
    override fun toHtml(value: String) = value
  }

  class Markdown(valueElement: PrimitiveType<*>) : TextRendering(valueElement) {
    override fun toHtml(value: String) = value.markdownToHtml()
  }
}

/** What to show for a string element. */
internal sealed class DisplayText {
  abstract fun toSpanned(): Spanned

  abstract fun toAnnotatedString(): AnnotatedString

  /** Formatted text from a rendering extension, as HTML. */
  data class Formatted(val html: String) : DisplayText() {
    override fun toSpanned() = html.toSpanned().trimTrailingNewlines()

    override fun toAnnotatedString() = html.toAnnotatedString().trimTrailingNewlines()
  }

  /** Plain text, shown as typed: `<`, `&` and line breaks are kept. */
  data class Plain(val text: String) : DisplayText() {
    override fun toSpanned(): Spanned = SpannedString(text)

    override fun toAnnotatedString() = AnnotatedString(text)
  }

  /**
   * Text without any rendering extension, parsed as HTML as the SDK always did, so existing content
   * that puts HTML in `text` does not change.
   */
  data class LegacyHtml(val text: String) : DisplayText() {
    override fun toSpanned() = text.toSpanned()

    override fun toAnnotatedString() = text.toAnnotatedString()
  }
}

private fun currentLanguage() = Locale.getDefault().toLanguageTag()

private fun StringType?.hasTranslation(lang: String): Boolean =
  this != null && (getTranslation(lang) != null || getTranslation(lang.split("-").first()) != null)

/**
 * The rendering extensions on a string element (`_text`, `_prefix`, `Coding._display`), XHTML
 * first. SDC: "if a form filler supports both, the filler SHOULD render the XHTML version".
 */
internal val StringType.textRenderings: List<TextRendering>
  get() =
    listOfNotNull(
      (getExtensionByUrl(EXTENSION_RENDERING_XHTML_URL)?.value as? PrimitiveType<*>)?.let {
        TextRendering.Xhtml(it)
      },
      (getExtensionByUrl(EXTENSION_RENDERING_MARKDOWN_URL)?.value as? PrimitiveType<*>)?.let {
        TextRendering.Markdown(it)
      },
    )

internal val StringType.hasTextRendering: Boolean
  get() =
    hasExtension(EXTENSION_RENDERING_XHTML_URL) || hasExtension(EXTENSION_RENDERING_MARKDOWN_URL)

/**
 * [text] as plain text when this element has a rendering extension (the element's plain value is
 * then plain by contract), otherwise parsed as HTML like before.
 */
internal fun StringType.plainDisplayText(text: String): DisplayText =
  if (hasTextRendering) DisplayText.Plain(text) else DisplayText.LegacyHtml(text)

/**
 * The plain value as a string, for `QuestionnaireResponse.item.text`: as typed when the element has
 * a rendering extension, otherwise the HTML-parsed text like before.
 */
internal fun StringType.localizedPlainText(): String? =
  getLocalizedText()?.let { if (hasTextRendering) it else it.toSpanned().toString() }

/**
 * Chooses what to show for a string element.
 * - Plain value live (`cqf-expression` on the element): the first rendering whose value is live
 *   (XHTML, then Markdown), evaluated with [evaluate]; otherwise the live plain value [livePlain].
 *   A static rendering would show a stale value, so it never hides live plain text.
 * - Plain value static: the first rendering (evaluated if its value is live and [evaluate] is
 *   given); otherwise the plain value. A rendering without a translation for the current language
 *   is skipped when the plain value has one.
 *
 * Without [evaluate] (prefix, answer options, nested display items) only static values are used.
 */
internal fun StringType.resolveDisplayText(
  livePlain: String? = null,
  evaluate: ((Expression) -> String?)? = null,
  lang: String = currentLanguage(),
): DisplayText? {
  val renderings = textRenderings
  if (renderings.isNotEmpty()) {
    if (cqfExpression != null) {
      renderings
        .firstOrNull { it.cqfExpression != null }
        ?.let { rendering ->
          evaluate
            ?.invoke(rendering.cqfExpression!!)
            ?.takeIf { it.isNotBlank() }
            ?.let {
              return DisplayText.Formatted(rendering.toHtml(it))
            }
        }
    } else {
      val plainTranslated = hasTranslation(lang)
      for (rendering in renderings) {
        if (plainTranslated && !rendering.hasTranslation(lang)) continue
        val value =
          rendering.cqfExpression?.let { evaluate?.invoke(it) } ?: rendering.staticValue(lang)
        if (!value.isNullOrBlank()) return DisplayText.Formatted(rendering.toHtml(value))
      }
    }
  }
  return (livePlain ?: getLocalizedText(lang))?.let { plainDisplayText(it) }
}

private fun Spanned.trimTrailingNewlines(): Spanned {
  var end = length
  while (end > 0 && this[end - 1] == '\n') end--
  return if (end == length) this else SpannableStringBuilder(this, 0, end)
}

private fun AnnotatedString.trimTrailingNewlines(): AnnotatedString {
  var end = length
  while (end > 0 && this[end - 1] == '\n') end--
  return if (end == length) this else subSequence(0, end)
}

private val BULLET = Regex("""^\s{0,3}[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^\s{0,3}(\d+[.)])\s+(.*)$""")
private val HEADING = Regex("""^\s{0,3}#{1,6}\s+(.*?)\s*#*\s*$""")
private val ESCAPED = Regex("""\\(&amp;|&lt;|&gt;|[!-/:-@\[-`{-~])""")
private val STRONG = Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""")
private val EM_STAR = Regex("""(?<!\*)\*(?=[^\s*])(.+?)(?<=[^\s*])\*(?!\*)""")
private val EM_UNDERSCORE = Regex("""(?<![_\p{L}\p{N}])_(?=\S)(.+?)(?<=\S)_(?![_\p{L}\p{N}])""")

/**
 * A minimal Markdown → HTML converter for labels, rendered by the same `HtmlCompat.fromHtml` as the
 * rest of the SDK: `**strong**`, `*em*` / `_em_` (no intraword `_`), backslash escapes, `-`/`*`/`+`
 * bullets, `1.` items (kept as text: Android shows `<ol>` items as bullets), `#` headings (bold),
 * paragraphs (blank line) and line breaks. Text is HTML-escaped first, so `<`, `>` and `&` in the
 * Markdown are shown as typed.
 */
internal fun String.markdownToHtml(): String {
  val out = StringBuilder()
  var list: String? = null
  val paragraph = mutableListOf<String>()

  fun inline(text: String): String {
    var t = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    // escapes become numeric entities so the mark regexes below do not see them
    t =
      ESCAPED.replace(t) { m ->
        val c = m.groupValues[1]
        if (c.length > 1) c else "&#${c[0].code};"
      }
    t = STRONG.replace(t) { "<b>${it.groupValues[2]}</b>" }
    t = EM_STAR.replace(t) { "<i>${it.groupValues[1]}</i>" }
    t = EM_UNDERSCORE.replace(t) { "<i>${it.groupValues[1]}</i>" }
    return t
  }

  fun flushParagraph() {
    if (paragraph.isNotEmpty()) {
      out.append("<p>").append(paragraph.joinToString("<br>")).append("</p>")
      paragraph.clear()
    }
  }

  fun closeList() {
    list?.let { out.append("</$it>") }
    list = null
  }

  for (raw in replace("\r\n", "\n").split('\n')) {
    val line = raw.trimEnd()
    val heading = HEADING.matchEntire(line)
    val bullet = BULLET.matchEntire(line)
    val numbered = NUMBERED.matchEntire(line)
    when {
      line.isBlank() -> {
        flushParagraph()
        closeList()
      }
      heading != null -> {
        flushParagraph()
        closeList()
        out.append("<p><b>").append(inline(heading.groupValues[1])).append("</b></p>")
      }
      bullet != null -> {
        flushParagraph()
        if (list == null) {
          out.append("<ul>")
          list = "ul"
        }
        out.append("<li>").append(inline(bullet.groupValues[1])).append("</li>")
      }
      // Android's Html renders <ol> items as bullets, so numbered items stay text lines
      numbered != null -> {
        closeList()
        paragraph.add(numbered.groupValues[1] + " " + inline(numbered.groupValues[2]))
      }
      else -> {
        closeList()
        paragraph.add(inline(line.trim()))
      }
    }
  }
  flushParagraph()
  closeList()
  return out.toString()
}
