package app.shilex.android.tools

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ContentProviderOperation
import android.content.Intent
import android.os.Build
import android.provider.ContactsContract
import app.shilex.android.Diag
import app.shilex.android.assistant.VoiceService
import app.shilex.android.control.ControlService
import app.shilex.android.data.Settings
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Custom A-to-Z tools for Misti:
 * - rename_assistant: changes Misti's name dynamically & updates wake/notification state
 * - unlock_phone: wakes & unlocks the lock screen (swipe + auto-enter saved PIN if configured)
 * - lock_phone: locks the device screen immediately via Accessibility
 * - save_contact: saves a new phone contact directly (with intent fallback)
 */
object AssistantTools {
    fun all(): List<PhoneTool> = listOf(RenameAssistant, UnlockPhone, LockPhone, SaveContact)

    private object RenameAssistant : PhoneTool {
        override val name = "rename_assistant"

        override fun declaration() = Schema.function(
            name,
            "Changes the assistant's own name whenever the user gives a new name (e.g., Misti, Janu, Priya, etc.).",
            Schema.string("new_name", "The new name the user wants to call the assistant.", required = true),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val newName = args.text("new_name")
            if (newName.isEmpty()) return Results.failed("no_name", "Ask what new name the user wants to give you.")
            val settings = Settings(host.context)
            val oldName = settings.assistantName
            settings.assistantName = newName
            VoiceService.start(host.context) // refreshes standby notification title with the new name
            Diag.i("persona: renamed from $oldName to $newName")
            return Results.done("Your name has been changed from $oldName to $newName. From now on you are $newName.")
        }
    }

    private object LockPhone : PhoneTool {
        override val name = "lock_phone"

        override fun declaration() = Schema.function(
            name,
            "Locks the phone screen immediately.",
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val control = ControlService.instance
                ?: return Results.failed("control_off", "Phone control (Accessibility) is off. Tell the user to turn it on in Settings.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val ok = control.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                if (ok) return Results.done("Phone locked.")
            }
            return Results.failed("lock_failed", "Couldn't lock the screen on this Android version.")
        }
    }

    private object UnlockPhone : PhoneTool {
        override val name = "unlock_phone"

        override fun declaration() = Schema.function(
            name,
            "Wakes up and unlocks the user's phone screen after voice verification.",
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val km = host.context.getSystemService(KeyguardManager::class.java)
            if (km != null && !km.isKeyguardLocked) {
                return Results.done("The phone is already unlocked.")
            }
            val control = ControlService.instance
                ?: return Results.failed("control_off", "Phone control (Accessibility) is off. Turn it on so I can unlock the screen.")
            val pin = Settings(host.context).unlockPin
            val unlocked = control.unlockDevice(pin)
            return if (unlocked) {
                Results.done("Phone unlocked for you.")
            } else if (pin.isEmpty()) {
                Results.done("Swiped up the lock screen. Set your Unlock PIN in Misti Settings for full hands-free PIN unlock.")
            } else {
                Results.failed("unlock_failed", "Woke the screen, please check if the PIN matched.")
            }
        }
    }

    private object SaveContact : PhoneTool {
        override val name = "save_contact"

        override fun declaration() = Schema.function(
            name,
            "Saves a new contact with name and phone number on the user's phone.",
            Schema.string("name", "Contact's full name.", required = true),
            Schema.string("number", "Phone number to save.", required = true),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val contactName = args.text("name")
            val number = args.text("number")
            if (contactName.isEmpty() || number.isEmpty()) {
                return Results.failed("missing_info", "Ask for both the contact name and phone number.")
            }
            // Try direct silent insert if WRITE_CONTACTS is granted
            if (host.hasPermission(android.Manifest.permission.WRITE_CONTACTS)) {
                val ops = ArrayList<ContentProviderOperation>().apply {
                    add(
                        ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                            .build()
                    )
                    add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, contactName)
                            .build()
                    )
                    add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                            .build()
                    )
                }
                val saved = runCatching {
                    host.context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                }.isSuccess
                if (saved) {
                    delay(200)
                    return Results.done("Saved $contactName ($number) to your contacts.")
                }
            }
            // Fallback: open prefilled Contacts insert screen
            val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                type = ContactsContract.RawContacts.CONTENT_TYPE
                putExtra(ContactsContract.Intents.Insert.NAME, contactName)
                putExtra(ContactsContract.Intents.Insert.PHONE, number)
            }
            return host.openApp(intent, "Contacts", "Opened Contacts with $contactName ($number) filled in.")
        }
    }
}
