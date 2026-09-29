package com.getfirepit.app.privacy

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Asks the keyboard not to learn from anything typed in [content].
 *
 * Keyboards keep what people type to improve suggestions, and some sync it to
 * an account. Messages, names and pins do not belong in that record. Put at the
 * root so every text field is covered, including ones added later.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoPersonalizedLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val private = PlatformTextInputMethodRequest { outAttributes ->
                request.createInputConnection(outAttributes).also {
                    outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                }
            }
            nextHandler.startInputMethod(private)
        },
        content = content,
    )
}
