package com.jarvis.assistant.data.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import androidx.core.content.ContextCompat
import com.jarvis.assistant.domain.ContactsSource
import com.jarvis.assistant.domain.contacts.Contact
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidContactsSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : ContactsSource {

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** One entry per contact that has a phone number, using its best number (primary > mobile > first). */
    override suspend fun loadContacts(): List<Contact> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val nicknames = loadNicknames()
        data class Row(val id: Long, val name: String, val number: String, val primary: Boolean, val type: Int)

        val rows = mutableListOf<Row>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.IS_SUPER_PRIMARY, Phone.TYPE),
            null,
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val number = c.getString(2) ?: continue
                rows += Row(c.getLong(0), name, number, c.getInt(3) != 0, c.getInt(4))
            }
        }

        rows.groupBy { it.id }.map { (id, numbers) ->
            val best = numbers.firstOrNull { it.primary }
                ?: numbers.firstOrNull { it.type == Phone.TYPE_MOBILE }
                ?: numbers.first()
            Contact(id, best.name, best.number, nicknames[id].orEmpty())
        }
    }

    private fun loadNicknames(): Map<Long, List<String>> {
        val result = mutableMapOf<Long, MutableList<String>>()
        context.contentResolver.query(
            Data.CONTENT_URI,
            arrayOf(Data.CONTACT_ID, Nickname.NAME),
            "${Data.MIMETYPE} = ?",
            arrayOf(Nickname.CONTENT_ITEM_TYPE),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val nick = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                result.getOrPut(c.getLong(0)) { mutableListOf() } += nick
            }
        }
        return result
    }
}
