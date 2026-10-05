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

import android.text.Spanned
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.fhir.datacapture.R
import com.google.android.fhir.datacapture.extensions.StyleUrl
import com.google.android.fhir.datacapture.extensions.appendAsteriskToQuestionText
import com.google.android.fhir.datacapture.extensions.applyCustomOrDefaultStyle
import com.google.android.fhir.datacapture.extensions.getLocalizedInstructionsSpanned
import com.google.android.fhir.datacapture.extensions.getStyleResIdFromAttribute
import com.google.android.fhir.datacapture.extensions.hasHelpButton
import com.google.android.fhir.datacapture.extensions.localizedFlyoverSpanned
import com.google.android.fhir.datacapture.extensions.localizedHelpSpanned
import com.google.android.fhir.datacapture.extensions.localizedPrefixSpanned
import com.google.android.fhir.datacapture.extensions.readCustomStyleExtension
import com.google.android.fhir.datacapture.validation.Invalid
import com.google.android.fhir.datacapture.validation.ValidationResult
import com.google.android.fhir.datacapture.views.QuestionnaireViewItem

@Composable
fun Header(
  questionnaireViewItem: QuestionnaireViewItem,
  displayValidationResult: Boolean = false,
  showRequiredOrOptionalText: Boolean = false,
) {
  val context = LocalContext.current
  val validationResult = questionnaireViewItem.validationResult
  val questionnaireItem = questionnaireViewItem.questionnaireItem
  val questionnaireResponseItem = questionnaireViewItem.getQuestionnaireResponseItem()
  val requiredOptionalText =
    when {
      (questionnaireItem.required &&
        questionnaireViewItem.questionViewTextConfiguration.showRequiredText) ->
        stringResource(R.string.required)
      (!questionnaireItem.required &&
        questionnaireViewItem.questionViewTextConfiguration.showOptionalText) ->
        stringResource(R.string.optional_helper_text)
      else -> null
    }

  val prefixLocalizedText = questionnaireViewItem.questionnaireItem.localizedPrefixSpanned
  val questionLocalizedText = appendAsteriskToQuestionText(context, questionnaireViewItem)
  val flyoverLocalizedText = questionnaireViewItem.enabledDisplayItems.localizedFlyoverSpanned
  val hintLocalizedText =
    questionnaireViewItem.enabledDisplayItems.getLocalizedInstructionsSpanned()
  val itemLocalizedHelpSpanned = questionnaireItem.localizedHelpSpanned

  //  This is to avoid an empty row in the questionnaire.
  if (
    listOf(
        prefixLocalizedText,
        questionLocalizedText,
        flyoverLocalizedText,
        hintLocalizedText,
        itemLocalizedHelpSpanned,
      )
      .any { !it.isNullOrBlank() } ||
      (showRequiredOrOptionalText && !requiredOptionalText.isNullOrBlank()) ||
      (displayValidationResult && validationResult is Invalid)
  ) {
    Header(
      prefixLocalizedText = prefixLocalizedText,
      questionLocalizedText = questionLocalizedText,
      readCustomStyleName = remember { { questionnaireItem.readCustomStyleExtension(it) } },
      flyoverLocalizedText = flyoverLocalizedText,
      isFlyoverCollapsed = questionnaireViewItem.isFlyoverCollapsed,
      onFlyoverCollapsedChange = { isCollapsed ->
        questionnaireViewItem.flyoverStateChangedCallback(
          isCollapsed,
          questionnaireViewItem.getQuestionnaireResponseItem(),
        )
      },
      hintLocalizedText = hintLocalizedText,
      isHelpCardOpen = questionnaireViewItem.isHelpCardOpen,
      isHelpButtonVisible = questionnaireItem.hasHelpButton,
      helpCardLocalizedText = itemLocalizedHelpSpanned,
      helpButtonOnClick = {
        questionnaireViewItem.helpCardStateChangedCallback(it, questionnaireResponseItem)
      },
      validationResult = validationResult,
      displayValidationResult = displayValidationResult,
      showRequiredOrOptionalText = showRequiredOrOptionalText,
      requiredOptionalText = requiredOptionalText,
    )
  }
}

@Composable
internal fun Header(
  prefixLocalizedText: Spanned?,
  questionLocalizedText: Spanned,
  readCustomStyleName: (StyleUrl) -> String?,
  flyoverLocalizedText: Spanned?,
  isFlyoverCollapsed: Boolean,
  onFlyoverCollapsedChange: (Boolean) -> Unit,
  hintLocalizedText: Spanned?,
  isHelpCardOpen: Boolean,
  isHelpButtonVisible: Boolean,
  helpCardLocalizedText: Spanned?,
  helpButtonOnClick: (Boolean) -> Unit,
  validationResult: ValidationResult,
  displayValidationResult: Boolean,
  showRequiredOrOptionalText: Boolean,
  requiredOptionalText: String?,
) {
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .padding(bottom = dimensionResource(R.dimen.header_padding_bottom))
        .testTag(HEADER_TAG),
  ) {
    PrefixQuestionTitle(prefixLocalizedText, questionLocalizedText, readCustomStyleName)

    if (!flyoverLocalizedText.isNullOrBlank()) {
      Flyover(
        flyoverLocalizedText,
        readCustomStyleName,
        isInitiallyCollapsed = isFlyoverCollapsed,
        onCollapsedChange = onFlyoverCollapsedChange,
      )
    }

    if (!hintLocalizedText.isNullOrBlank() || isHelpButtonVisible || isHelpCardOpen) {
      Help(
        hintLocalizedText,
        readCustomStyleName,
        isHelpCardOpen,
        isHelpButtonVisible,
        helpButtonOnClick,
        helpCardLocalizedText,
      )
    }

    // Required/Optional Text
    if (showRequiredOrOptionalText && !requiredOptionalText.isNullOrBlank()) {
      Text(text = requiredOptionalText, style = MaterialTheme.typography.bodyMedium)
    }

    // Validation Error
    if (displayValidationResult && validationResult is Invalid) {
      Text(
        modifier = Modifier.testTag(ERROR_TEXT_AT_HEADER_TEST_TAG),
        text = validationResult.getSingleStringValidationMessage(),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

@Composable
internal fun PrefixQuestionTitle(
  prefixLocalizedText: Spanned?,
  questionLocalizedText: Spanned,
  readCustomStyleName: (StyleUrl) -> String?,
) {
  Row(modifier = Modifier.fillMaxWidth()) {
    if (!prefixLocalizedText.isNullOrBlank()) {
      AndroidView(
        factory = {
          TextView(it).apply {
            id = R.id.prefix
            applyCustomOrDefaultStyle(
              context = it,
              view = this,
              customStyleName =
                readCustomStyleName(
                  StyleUrl.PREFIX_TEXT_VIEW,
                ),
              defaultStyleResId =
                getStyleResIdFromAttribute(it, R.attr.questionnaireQuestionTextStyle),
            )
          }
        },
        update = { it.text = prefixLocalizedText },
      )
      Spacer(modifier = Modifier.width(dimensionResource(R.dimen.prefix_padding_end)))
    }
    AndroidView(
      factory = {
        TextView(it).apply {
          id = R.id.question
          movementMethod = LinkMovementMethod.getInstance()
          applyCustomOrDefaultStyle(
            context = it,
            view = this,
            customStyleName =
              readCustomStyleName(
                StyleUrl.QUESTION_TEXT_VIEW,
              ),
            defaultStyleResId =
              getStyleResIdFromAttribute(it, R.attr.questionnaireQuestionTextStyle),
          )
        }
      },
      modifier = Modifier.weight(1f),
      update = { it.text = questionLocalizedText },
    )
  }
}

@Composable
internal fun Flyover(
  flyoverLocalizedText: Spanned,
  readCustomStyleName: (StyleUrl) -> String?,
  isInitiallyCollapsed: Boolean,
  onCollapsedChange: (Boolean) -> Unit,
) {
  // Local state drives this composition; onCollapsedChange only persists it in the view model, so
  // deriving `expanded` from the parameter alone would never recompose on tap.
  var isCollapsed by remember(isInitiallyCollapsed) { mutableStateOf(isInitiallyCollapsed) }
  val expanded = !isCollapsed

  Row(
    modifier =
      Modifier.fillMaxWidth()
        .padding(top = dimensionResource(R.dimen.help_container_margin_top))
        .clickable {
          isCollapsed = !isCollapsed
          onCollapsedChange(isCollapsed)
        }
        .testTag(FLYOVER_TAG),
    verticalAlignment = Alignment.Top,
  ) {
    AndroidView(
      modifier = Modifier.weight(1f),
      factory = {
        TextView(it).apply {
          id = R.id.flyover_text
          movementMethod = LinkMovementMethod.getInstance()
          applyCustomOrDefaultStyle(
            context = it,
            view = this,
            customStyleName = readCustomStyleName(StyleUrl.FLYOVER_TEXT_VIEW),
            defaultStyleResId =
              getStyleResIdFromAttribute(it, R.attr.questionnaireFlyoverTextStyle),
          )
        }
      },
      update = { textView ->
        textView.text = flyoverLocalizedText
        textView.maxLines = if (expanded) Integer.MAX_VALUE else 1
        textView.ellipsize = if (expanded) null else TextUtils.TruncateAt.END
      },
    )
    Icon(
      painterResource(R.drawable.expand_more_24px),
      contentDescription =
        stringResource(if (expanded) R.string.flyover_collapse else R.string.flyover_expand),
      modifier =
        Modifier.padding(start = dimensionResource(R.dimen.help_button_margin_start))
          .size(
            width = dimensionResource(R.dimen.help_button_width),
            height = dimensionResource(R.dimen.help_button_height),
          )
          .rotate(if (expanded) 180f else 0f)
          .testTag(FLYOVER_EXPAND_ICON_TAG),
    )
  }
}

@Composable
internal fun Help(
  hintLocalizedText: Spanned?,
  readCustomStyleName: (StyleUrl) -> String?,
  isHelpCardInitiallyOpen: Boolean,
  isHelpButtonVisible: Boolean,
  helpButtonOnClick: (Boolean) -> Unit,
  helpCardLocalizedText: Spanned?,
) {
  var isCardOpen by remember { mutableStateOf(isHelpCardInitiallyOpen) }

  Row(
    modifier = Modifier.padding(top = dimensionResource(R.dimen.help_container_margin_top)),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    hintLocalizedText?.let {
      AndroidView(
        modifier = Modifier.weight(0.7f),
        factory = {
          TextView(it).apply {
            id = R.id.hint
            movementMethod = LinkMovementMethod.getInstance()
            applyCustomOrDefaultStyle(
              context = it,
              view = this,
              customStyleName =
                readCustomStyleName(
                  StyleUrl.SUBTITLE_TEXT_VIEW,
                ),
              defaultStyleResId =
                getStyleResIdFromAttribute(it, R.attr.questionnaireSubtitleTextStyle),
            )
          }
        },
        update = { it.text = hintLocalizedText },
      )
    }

    if (isHelpButtonVisible) {
      IconButton(
        onClick = {
          isCardOpen = !isCardOpen
          helpButtonOnClick(isCardOpen)
        },
        modifier =
          Modifier.padding(start = dimensionResource(R.dimen.help_button_margin_start))
            .testTag(HELP_BUTTON_TAG)
            .weight(0.3f)
            .size(
              width = dimensionResource(R.dimen.help_button_width),
              height = dimensionResource(R.dimen.help_button_height),
            ),
      ) {
        Icon(
          painterResource(R.drawable.ic_help_48px),
          contentDescription = stringResource(R.string.help),
        )
      }
    }
  }

  if (isCardOpen) {
    Card(modifier = Modifier.padding(top = 8.dp).testTag(HELP_CARD_TAG)) {
      Column(modifier = Modifier.padding(8.dp)) {
        Text(
          text = stringResource(id = R.string.help),
          modifier =
            Modifier.padding(horizontal = dimensionResource(R.dimen.help_header_margin_horizontal)),
          style = MaterialTheme.typography.titleSmall,
        )

        AndroidView(
          factory = {
            TextView(it).apply {
              id = R.id.helpText
              movementMethod = LinkMovementMethod.getInstance()
            }
          },
          modifier =
            Modifier.padding(horizontal = dimensionResource(R.dimen.help_text_margin_horizontal)),
          update = { it.text = helpCardLocalizedText },
        )
      }
    }
  }
}

const val ERROR_TEXT_AT_HEADER_TEST_TAG = "error_text_at_header"
const val HELP_BUTTON_TAG = "helpButton"
const val HELP_CARD_TAG = "helpCardView"
const val HEADER_TAG = "headerView"
const val FLYOVER_TAG = "flyoverView"
const val FLYOVER_EXPAND_ICON_TAG = "flyoverExpandIcon"
