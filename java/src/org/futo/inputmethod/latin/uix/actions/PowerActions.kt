package org.futo.inputmethod.latin.uix.actions

import android.view.KeyEvent
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.PersonalWord
import org.futo.inputmethod.latin.uix.UserDictionaryIO

val ForwardDeleteAction = Action(
    icon = R.drawable.delete,
    name = R.string.action_forward_delete_title,
    simplePressImpl = { manager, _ ->
        manager.sendKeyEvent(KeyEvent.KEYCODE_FORWARD_DEL, 0)
    },
    windowImpl = null,
)

val AddCurrentWordAction = Action(
    icon = R.drawable.plus_circle,
    name = R.string.action_add_word_title,
    simplePressImpl = { manager, _ ->
        val word = manager.getSelectedOrCurrentWord()
        if (word.isNullOrBlank()) {
            manager.announce(manager.getContext().getString(R.string.action_add_word_empty))
        } else {
            val locale = manager.getActiveLocales().firstOrNull()?.toString()
            UserDictionaryIO(manager.getContext()).put(
                listOf(PersonalWord(word, 250, locale, 0, null)),
                clear = false,
            )
            manager.announce(manager.getContext().getString(R.string.action_add_word_added))
        }
    },
    windowImpl = null,
)
