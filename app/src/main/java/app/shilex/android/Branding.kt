package app.shilex.android

import android.content.Context
import app.shilex.android.data.Settings

/** Dynamic & default user-visible names for the assistant. */
object Branding {
    const val DEFAULT_NAME = "Misti"
    const val ASSISTANT_NAME = "Misti"

    fun currentName(context: Context): String =
        Settings(context).assistantName.trim().ifEmpty { DEFAULT_NAME }
}
