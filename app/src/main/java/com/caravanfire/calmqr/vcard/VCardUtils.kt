package com.caravanfire.calmqr.vcard

import android.content.ContentValues
import android.content.Intent
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website

/**
 * Parsed contact from a vCard (BEGIN:VCARD…END:VCARD) or MECARD QR code.
 *
 * Field semantics roughly follow vCard 3.0; unspecified `type` values fall back
 * to `TYPE_OTHER` from the matching `ContactsContract.CommonDataKinds` table.
 */
data class VCardContact(
    val displayName: String,
    val givenName: String?,
    val familyName: String?,
    val phones: List<TypedString>,
    val emails: List<TypedString>,
    val organization: String?,
    val title: String?,
    val addresses: List<TypedString>,
    val urls: List<String>,
    val notes: List<String>,
    val birthday: String?,
) {
    /** A value plus its ContactsContract type integer (e.g. Phone.TYPE_MOBILE). */
    data class TypedString(val value: String, val type: Int)
}

/** True if [content] looks like a vCard or MECARD payload. */
fun isVCardQrCode(content: String): Boolean {
    val s = content.trimStart()
    return s.startsWith("BEGIN:VCARD", ignoreCase = true) ||
        s.startsWith("MECARD:", ignoreCase = true)
}

/** Parse a vCard or MECARD string; returns null if the payload is not recognizable. */
fun parseVCard(content: String): VCardContact? {
    val trimmed = content.trim()
    return when {
        trimmed.startsWith("BEGIN:VCARD", ignoreCase = true) -> parseFullVCard(trimmed)
        trimmed.startsWith("MECARD:", ignoreCase = true) -> parseMeCard(trimmed)
        else -> null
    }
}

/**
 * Build an `ACTION_INSERT` intent pre-populated with the contact's data via the
 * `Intents.Insert.DATA` ArrayList. The user sees the system Contacts editor
 * with all fields filled in and taps Save. On a degoogled device the only
 * account available is the local "Phone" account, so no picker appears.
 */
fun buildContactInsertIntent(contact: VCardContact): Intent {
    val intent = Intent(Intent.ACTION_INSERT).apply {
        type = ContactsContract.Contacts.CONTENT_TYPE
        // NAME extra is shown in the editor's title bar even before DATA is read,
        // and on some Contacts implementations (including AOSP variants on
        // degoogled devices) it acts as the fallback display when the
        // StructuredName row's components don't fully populate.
        putExtra(ContactsContract.Intents.Insert.NAME, contact.displayName)
    }

    val data = arrayListOf<ContentValues>()

    if (contact.givenName != null || contact.familyName != null) {
        data += ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
            put(StructuredName.DISPLAY_NAME, contact.displayName)
            contact.givenName?.let { put(StructuredName.GIVEN_NAME, it) }
            contact.familyName?.let { put(StructuredName.FAMILY_NAME, it) }
        }
    } else if (contact.displayName.isNotBlank()) {
        // No structured N components — route the formatted name (FN) into the
        // given-name field so editors without a dedicated "Display Name" field
        // (e.g. Mudita Kompakt's contact editor) still show the value somewhere.
        data += ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
            put(StructuredName.DISPLAY_NAME, contact.displayName)
            put(StructuredName.GIVEN_NAME, contact.displayName)
        }
    }

    contact.phones.forEach { p ->
        data += ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
            put(Phone.NUMBER, p.value)
            put(Phone.TYPE, p.type)
        }
    }

    contact.emails.forEach { e ->
        data += ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
            put(Email.ADDRESS, e.value)
            put(Email.TYPE, e.type)
        }
    }

    if (contact.organization != null || contact.title != null) {
        data += ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE)
            contact.organization?.let { put(Organization.COMPANY, it) }
            contact.title?.let { put(Organization.TITLE, it) }
            put(Organization.TYPE, Organization.TYPE_WORK)
        }
    }

    contact.addresses.forEach { a ->
        data += ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE)
            put(StructuredPostal.FORMATTED_ADDRESS, a.value)
            put(StructuredPostal.TYPE, a.type)
        }
    }

    contact.urls.forEach { url ->
        data += ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, Website.CONTENT_ITEM_TYPE)
            put(Website.URL, url)
            put(Website.TYPE, Website.TYPE_OTHER)
        }
    }

    contact.notes.forEach { note ->
        data += ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
            put(Note.NOTE, note)
        }
    }

    contact.birthday?.let { bday ->
        data += ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, Event.CONTENT_ITEM_TYPE)
            put(Event.START_DATE, bday)
            put(Event.TYPE, Event.TYPE_BIRTHDAY)
        }
    }

    intent.putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, data)
    return intent
}

// ── vCard 3.0 parser ────────────────────────────────────────────────────────

private fun parseFullVCard(content: String): VCardContact? {
    // vCard allows line folding: lines starting with whitespace continue the
    // previous logical line. Unfold first.
    val unfolded = unfoldLines(content)
    val lines = unfolded.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("BEGIN:", true) &&
                  !it.startsWith("END:", true) && !it.startsWith("VERSION:", true) }
        .toList()

    val phones = mutableListOf<VCardContact.TypedString>()
    val emails = mutableListOf<VCardContact.TypedString>()
    val addresses = mutableListOf<VCardContact.TypedString>()
    val urls = mutableListOf<String>()
    val notes = mutableListOf<String>()
    var displayName: String? = null
    var givenName: String? = null
    var familyName: String? = null
    var organization: String? = null
    var title: String? = null
    var birthday: String? = null

    for (line in lines) {
        val colon = line.indexOf(':')
        if (colon < 0) continue
        val keyPart = line.substring(0, colon)
        val rawValue = line.substring(colon + 1)
        val value = unescapeValue(rawValue)

        val semi = keyPart.indexOf(';')
        val key = if (semi < 0) keyPart else keyPart.substring(0, semi)
        val params = if (semi < 0) "" else keyPart.substring(semi + 1)

        when (key.uppercase()) {
            "FN" -> displayName = value
            "N" -> {
                // N:Family;Given;Middle;Prefix;Suffix (semicolons unescaped above)
                val parts = rawValue.split(';')
                familyName = parts.getOrNull(0)?.let(::unescapeValue)?.ifBlank { null }
                givenName = parts.getOrNull(1)?.let(::unescapeValue)?.ifBlank { null }
            }
            "TEL" -> phones += VCardContact.TypedString(value, parsePhoneType(params))
            "EMAIL" -> emails += VCardContact.TypedString(value, parseEmailType(params))
            "ORG" -> organization = rawValue.split(';').firstOrNull()?.let(::unescapeValue)
            "TITLE" -> title = value
            "ADR" -> {
                // ADR:PO-Box;Ext;Street;Locality;Region;PostalCode;Country
                val parts = rawValue.split(';').map(::unescapeValue)
                val formatted = listOf(
                    parts.getOrNull(2),
                    parts.getOrNull(3),
                    parts.getOrNull(4),
                    parts.getOrNull(5),
                    parts.getOrNull(6),
                ).mapNotNull { it?.takeIf(String::isNotBlank) }.joinToString(", ")
                if (formatted.isNotBlank()) {
                    addresses += VCardContact.TypedString(formatted, parseAddressType(params))
                }
            }
            "URL" -> urls += value
            "NOTE" -> notes += value
            "BDAY" -> birthday = value
        }
    }

    val finalName = displayName
        ?: listOfNotNull(givenName, familyName).joinToString(" ").ifBlank { null }
        ?: return null

    return VCardContact(
        displayName = finalName,
        givenName = givenName,
        familyName = familyName,
        phones = phones,
        emails = emails,
        organization = organization,
        title = title,
        addresses = addresses,
        urls = urls,
        notes = notes,
        birthday = birthday,
    )
}

// ── MECARD parser ────────────────────────────────────────────────────────────

private fun parseMeCard(content: String): VCardContact? {
    val body = content.removePrefix("MECARD:")
        .removePrefix("mecard:")
        .trimEnd(';')
    val pairs = splitMecardFields(body)

    val phones = mutableListOf<VCardContact.TypedString>()
    val emails = mutableListOf<VCardContact.TypedString>()
    val addresses = mutableListOf<VCardContact.TypedString>()
    val urls = mutableListOf<String>()
    val notes = mutableListOf<String>()
    var displayName: String? = null
    var givenName: String? = null
    var familyName: String? = null
    var birthday: String? = null

    for (pair in pairs) {
        val colon = pair.indexOf(':')
        if (colon < 0) continue
        val key = pair.substring(0, colon)
        val value = unescapeValue(pair.substring(colon + 1))

        when (key.uppercase()) {
            "N" -> {
                // MECARD uses "Last,First" (comma-separated)
                val parts = value.split(',')
                familyName = parts.getOrNull(0)?.ifBlank { null }
                givenName = parts.getOrNull(1)?.ifBlank { null }
                displayName = listOfNotNull(givenName, familyName).joinToString(" ").ifBlank { value }
            }
            "TEL" -> phones += VCardContact.TypedString(value, Phone.TYPE_MOBILE)
            "EMAIL" -> emails += VCardContact.TypedString(value, Email.TYPE_OTHER)
            "ADR" -> addresses += VCardContact.TypedString(value, StructuredPostal.TYPE_OTHER)
            "URL" -> urls += value
            "NOTE" -> notes += value
            "BDAY" -> birthday = value
        }
    }

    val finalName = displayName ?: return null
    return VCardContact(
        displayName = finalName,
        givenName = givenName,
        familyName = familyName,
        phones = phones,
        emails = emails,
        organization = null,
        title = null,
        addresses = addresses,
        urls = urls,
        notes = notes,
        birthday = birthday,
    )
}

/** Split MECARD on unescaped ';'. */
private fun splitMecardFields(body: String): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (c == '\\' && i + 1 < body.length) {
            sb.append(body[i + 1])
            i += 2
            continue
        }
        if (c == ';') {
            if (sb.isNotEmpty()) out += sb.toString()
            sb.setLength(0)
        } else {
            sb.append(c)
        }
        i++
    }
    if (sb.isNotEmpty()) out += sb.toString()
    return out
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun unfoldLines(content: String): String {
    // Per RFC 6350: a CRLF followed by a space or tab is a line fold.
    val normalised = content.replace("\r\n", "\n").replace("\r", "\n")
    val sb = StringBuilder(normalised.length)
    var i = 0
    while (i < normalised.length) {
        val c = normalised[i]
        if (c == '\n' && i + 1 < normalised.length &&
            (normalised[i + 1] == ' ' || normalised[i + 1] == '\t')
        ) {
            i += 2 // skip the newline and the leading whitespace, joining the line
            continue
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

private fun unescapeValue(s: String): String = buildString(s.length) {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (val n = s[i + 1]) {
                'n', 'N' -> append('\n')
                ',', ';', '\\' -> append(n)
                else -> append(n)
            }
            i += 2
        } else {
            append(c)
            i++
        }
    }
}

private fun parsePhoneType(params: String): Int {
    val u = params.uppercase()
    return when {
        "FAX" in u && "WORK" in u -> Phone.TYPE_FAX_WORK
        "FAX" in u -> Phone.TYPE_FAX_HOME
        "CELL" in u || "MOBILE" in u -> Phone.TYPE_MOBILE
        "WORK" in u -> Phone.TYPE_WORK
        "HOME" in u -> Phone.TYPE_HOME
        "PAGER" in u -> Phone.TYPE_PAGER
        else -> Phone.TYPE_OTHER
    }
}

private fun parseEmailType(params: String): Int {
    val u = params.uppercase()
    return when {
        "WORK" in u -> Email.TYPE_WORK
        "HOME" in u -> Email.TYPE_HOME
        else -> Email.TYPE_OTHER
    }
}

private fun parseAddressType(params: String): Int {
    val u = params.uppercase()
    return when {
        "WORK" in u -> StructuredPostal.TYPE_WORK
        "HOME" in u -> StructuredPostal.TYPE_HOME
        else -> StructuredPostal.TYPE_OTHER
    }
}
