package org.futo.inputmethod.latin.autofill

import android.content.Context
import android.text.InputType
import android.util.AtomicFile
import android.view.inputmethod.EditorInfo
import org.futo.inputmethod.latin.Dictionary
import org.futo.inputmethod.latin.InputAttributes
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.futo.inputmethod.latin.settings.SettingsValues
import org.futo.inputmethod.latin.uix.DataStoreHelper
import org.futo.inputmethod.latin.uix.settings.pages.FieldAutofillSetting
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.max

enum class FieldAutofillType {
    EMAIL,
    PHONE,
    FIRST_NAME,
    LAST_NAME,
    FULL_NAME,
    USERNAME,
    ORGANIZATION,
    ADDRESS,
    CITY,
    STATE,
    POSTAL_CODE,
    UNKNOWN
}

data class FieldAutofillField(
    val packageName: String,
    val fieldKey: String,
    val type: FieldAutofillType,
    val tokens: Set<String>
)

private data class StoredFieldValue(
    val value: String,
    val type: FieldAutofillType,
    val fieldKey: String,
    val packageName: String,
    var useCount: Int,
    var lastUsed: Long
)

class FieldAutofillManager(private val context: Context) {
    private val store = FieldAutofillStore(context)
    private var currentField: FieldAutofillField? = null
    private val sessionTypes = ArrayDeque<FieldAutofillType>()

    fun onStartInput(editorInfo: EditorInfo?, inputAttributes: InputAttributes?) {
        currentField = classify(editorInfo, inputAttributes)
        currentField?.let {
            sessionTypes.remove(it.type)
            sessionTypes.addFirst(it.type)
            while (sessionTypes.size > MAX_SESSION_FIELDS) sessionTypes.removeLast()
        }
    }

    fun onFinishInput(value: String?, inputAttributes: InputAttributes?, settingsValues: SettingsValues?) {
        val field = currentField ?: return
        if (!isEnabled(inputAttributes, settingsValues)) return

        val normalized = normalizeValue(value, field.type) ?: return
        if (!isValueAllowedForType(normalized, field.type)) return

        store.learn(field, normalized)
    }

    fun suggestionsFor(
        editorInfo: EditorInfo?,
        inputAttributes: InputAttributes?,
        settingsValues: SettingsValues?,
        prefix: String?
    ): ArrayList<SuggestedWordInfo> {
        val field = classify(editorInfo, inputAttributes) ?: return arrayListOf()
        if (!isEnabled(inputAttributes, settingsValues)) return arrayListOf()

        val normalizedPrefix = normalizePrefix(prefix, field.type)
        val values = store.suggest(field, normalizedPrefix, sessionTypes.toList())
        return ArrayList(values.mapIndexed { index, value ->
            SuggestedWordInfo(
                value,
                "$AUTOFILL_CONTEXT_PREFIX${normalizedPrefix.length}",
                SuggestedWordInfo.MAX_SCORE - index,
                SuggestedWordInfo.KIND_AUTOFILL,
                Dictionary.DICTIONARY_USER_TYPED,
                SuggestedWordInfo.NOT_AN_INDEX,
                SuggestedWordInfo.NOT_A_CONFIDENCE,
                0,
                "Autofill"
            ).apply {
                setDebugString("autofill:${field.type.name.lowercase(Locale.ROOT)}")
            }
        })
    }

    fun onSuggestionAccepted(suggestionInfo: SuggestedWordInfo, settingsValues: SettingsValues?) {
        if (!suggestionInfo.isKindOf(SuggestedWordInfo.KIND_AUTOFILL)) return
        if (!isEnabled(settingsValues?.mInputAttributes, settingsValues)) return
        val field = currentField ?: return
        store.markUsed(field, suggestionInfo.mWord)
    }

    fun extractCurrentFieldValue(beforeCursor: CharSequence?, afterCursor: CharSequence?): String? {
        val before = beforeCursor?.toString().orEmpty()
        val after = afterCursor?.toString().orEmpty()
        if (before.length + after.length > MAX_VALUE_LENGTH) return null
        return trimFieldBoundary(before, keepAfterLastLine = true) +
                trimFieldBoundary(after, keepBeforeFirstLine = true)
    }

    fun extractPrefix(beforeCursor: CharSequence?): String {
        return trimFieldBoundary(beforeCursor?.toString().orEmpty(), keepAfterLastLine = true)
    }

    private fun isEnabled(inputAttributes: InputAttributes?, settingsValues: SettingsValues?): Boolean {
        return DataStoreHelper.getSetting(FieldAutofillSetting)
                && settingsValues?.isPersonalizationEnabled == true
                && inputAttributes?.mNoLearning != true
                && inputAttributes?.mIsPasswordField != true
                && inputAttributes?.mIsCodeField != true
                && inputAttributes?.mIsUriField != true
    }

    companion object {
        const val AUTOFILL_CONTEXT_PREFIX = "field_autofill:"
        private const val MAX_VALUE_LENGTH = 160
        private const val MAX_SESSION_FIELDS = 12

        fun clear(context: Context) {
            FieldAutofillStore(context).clear()
        }

        fun classify(editorInfo: EditorInfo?, inputAttributes: InputAttributes?): FieldAutofillField? {
            if (editorInfo == null || inputAttributes == null) return null
            if (inputAttributes.mNoLearning || inputAttributes.mIsPasswordField ||
                    inputAttributes.mIsCodeField || inputAttributes.mIsUriField) {
                return null
            }

            val packageName = editorInfo.packageName ?: return null
            val tokenSource = listOfNotNull(
                editorInfo.fieldName,
                editorInfo.hintText?.toString(),
                editorInfo.label?.toString(),
                editorInfo.privateImeOptions,
                editorInfo.extras?.keySet()?.joinToString(" ")
            ).joinToString(" ")

            val tokens = tokenize(tokenSource)
            if (tokens.isEmpty() && (editorInfo.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_PHONE) {
                return null
            }
            if (isSensitive(tokens, tokenSource)) return null

            val type = classifyType(editorInfo.inputType, tokens)
            if (type == FieldAutofillType.UNKNOWN) return null

            val stableTokens = tokens
                .filterNot { it.length <= 1 || it.all(Char::isDigit) }
                .take(8)
                .joinToString("_")
                .ifBlank { "field_${editorInfo.fieldId}" }
            val fieldKey = listOf(packageName, type.name, stableTokens, editorInfo.fieldId)
                .joinToString("|")

            return FieldAutofillField(packageName, fieldKey, type, tokens)
        }

        private fun classifyType(inputType: Int, tokens: Set<String>): FieldAutofillType {
            val inputClass = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION

            if (inputClass == InputType.TYPE_CLASS_PHONE) return FieldAutofillType.PHONE
            if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                    tokens.any { it in setOf("email", "mail") }) {
                return FieldAutofillType.EMAIL
            }

            return when {
                tokens.any { it in setOf("phone", "mobile", "tel", "telephone", "cell") } -> FieldAutofillType.PHONE
                tokens.any { it in setOf("zipcode", "zip", "postal", "postcode", "postalcode") } -> FieldAutofillType.POSTAL_CODE
                hasTokenPair(tokens, "first", "name") || tokens.any { it in setOf("firstname", "fname", "given", "givenname") } -> FieldAutofillType.FIRST_NAME
                hasTokenPair(tokens, "last", "name") || tokens.any { it in setOf("lastname", "lname", "surname", "family", "familyname") } -> FieldAutofillType.LAST_NAME
                hasTokenPair(tokens, "user", "name") ||
                        tokens.any { it in setOf("username", "login", "handle") } -> FieldAutofillType.USERNAME
                tokens.any { it == "fullname" } ||
                        (tokens.any { it == "name" } && tokens.none { it in setOf("user", "login", "handle") }) ||
                        variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> FieldAutofillType.FULL_NAME
                tokens.any { it in setOf("company", "organization", "organisation", "org", "business") } -> FieldAutofillType.ORGANIZATION
                tokens.any { it in setOf("address", "addr", "street", "streetaddress", "line1", "line2") } -> FieldAutofillType.ADDRESS
                tokens.any { it in setOf("city", "locality", "town") } -> FieldAutofillType.CITY
                tokens.any { it in setOf("state", "province", "region") } -> FieldAutofillType.STATE
                else -> FieldAutofillType.UNKNOWN
            }
        }

        private fun normalizeValue(value: String?, type: FieldAutofillType): String? {
            val collapsed = value
                ?.replace('\u0000', ' ')
                ?.replace(Regex("[\\t\\r ]+"), " ")
                ?.trim()
                ?: return null
            if (collapsed.isBlank() || collapsed.length > MAX_VALUE_LENGTH) return null
            if (type != FieldAutofillType.ADDRESS && collapsed.contains('\n')) return null
            if (collapsed.all { it == '*' || it == '•' || it == '.' }) return null
            return if (type == FieldAutofillType.EMAIL) {
                collapsed.lowercase(Locale.ROOT)
            } else {
                collapsed.replace(Regex("\\n+"), "\n")
            }
        }

        private fun normalizePrefix(prefix: String?, type: FieldAutofillType): String {
            return normalizeValue(prefix, type).orEmpty()
        }

        private fun isValueAllowedForType(value: String, type: FieldAutofillType): Boolean {
            return when (type) {
                FieldAutofillType.EMAIL -> Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(value)
                FieldAutofillType.PHONE -> value.count(Char::isDigit) >= 7
                FieldAutofillType.POSTAL_CODE -> Regex("^[A-Za-z0-9][A-Za-z0-9 \\-]{1,11}$").matches(value)
                FieldAutofillType.STATE -> value.length in 2..40 && !looksLikeSecret(value)
                FieldAutofillType.CITY,
                FieldAutofillType.FIRST_NAME,
                FieldAutofillType.LAST_NAME,
                FieldAutofillType.FULL_NAME,
                FieldAutofillType.USERNAME,
                FieldAutofillType.ORGANIZATION -> value.length in 2..80 &&
                        !value.contains('@') && !looksLikeUrl(value) && !looksLikeSecret(value)
                FieldAutofillType.ADDRESS -> value.length in 4..MAX_VALUE_LENGTH &&
                        !looksLikeUrl(value) && !looksLikeSecret(value)
                FieldAutofillType.UNKNOWN -> false
            }
        }

        private fun trimFieldBoundary(
            value: String,
            keepAfterLastLine: Boolean = false,
            keepBeforeFirstLine: Boolean = false
        ): String {
            var result = value
            if (keepAfterLastLine) result = result.substringAfterLast('\n')
            if (keepBeforeFirstLine) result = result.substringBefore('\n')
            return result.take(MAX_VALUE_LENGTH)
        }

        private fun tokenize(value: String): Set<String> {
            val camelSplit = value.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            return camelSplit
                .lowercase(Locale.ROOT)
                .split(Regex("[^a-z0-9]+"))
                .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .toSet()
        }

        private fun hasTokenPair(tokens: Set<String>, first: String, second: String): Boolean {
            return first in tokens && second in tokens
        }

        private fun isSensitive(tokens: Set<String>, raw: String): Boolean {
            val rawLower = raw.lowercase(Locale.ROOT)
            val sensitiveTokens = setOf(
                "pass", "password", "pwd", "passcode", "otp", "2fa", "mfa", "code",
                "pin", "cvv", "cvc", "card", "cc", "credit", "debit", "ssn", "sin",
                "secret", "token", "auth", "security", "verification", "verify"
            )
            if (tokens.any { it in sensitiveTokens }) return true
            return listOf("password", "passcode", "one-time", "creditcard", "cardnumber", "securitycode")
                .any { rawLower.contains(it) }
        }

        private fun looksLikeUrl(value: String): Boolean {
            val lower = value.lowercase(Locale.ROOT)
            return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www.")
        }

        private fun looksLikeSecret(value: String): Boolean {
            if (value.length >= 24 && value.count(Char::isLetterOrDigit) >= value.length - 2) return true
            return Regex("^[0-9]{12,19}$").matches(value.replace(" ", ""))
        }
    }
}

private class FieldAutofillStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "field_autofill.json"))
    private val lock = Any()
    private var loaded = false
    private val values = mutableListOf<StoredFieldValue>()

    fun learn(field: FieldAutofillField, value: String) = synchronized(lock) {
        loadLocked()
        val now = System.currentTimeMillis()
        val existing = values.firstOrNull {
            it.type == field.type &&
                    it.fieldKey == field.fieldKey &&
                    it.value.equals(value, ignoreCase = field.type == FieldAutofillType.EMAIL)
        }

        if (existing != null) {
            existing.useCount += 1
            existing.lastUsed = now
        } else {
            values.add(
                StoredFieldValue(
                    value = value,
                    type = field.type,
                    fieldKey = field.fieldKey,
                    packageName = field.packageName,
                    useCount = 1,
                    lastUsed = now
                )
            )
        }
        pruneLocked()
        saveLocked()
    }

    fun markUsed(field: FieldAutofillField, value: String) = synchronized(lock) {
        loadLocked()
        val now = System.currentTimeMillis()
        values.firstOrNull {
            it.type == field.type &&
                    it.fieldKey == field.fieldKey &&
                    it.value.equals(value, ignoreCase = field.type == FieldAutofillType.EMAIL)
        } ?: values.firstOrNull {
            it.type == field.type && it.value.equals(value, ignoreCase = field.type == FieldAutofillType.EMAIL)
        }?.let {
            it.useCount += 1
            it.lastUsed = now
            saveLocked()
        }
    }

    fun suggest(field: FieldAutofillField, prefix: String, sessionTypes: List<FieldAutofillType>): List<String> = synchronized(lock) {
        loadLocked()
        values.asSequence()
            .filter { it.type == field.type }
            .filter {
                prefix.isBlank() || it.value.startsWith(prefix, ignoreCase = true)
            }
            .sortedWith(
                compareByDescending<StoredFieldValue> { it.fieldKey == field.fieldKey }
                    .thenByDescending { it.packageName == field.packageName }
                    .thenByDescending { it.type in sessionTypes }
                    .thenByDescending { it.useCount }
                    .thenByDescending { it.lastUsed }
            )
            .map { it.value }
            .distinctBy { if (field.type == FieldAutofillType.EMAIL) it.lowercase(Locale.ROOT) else it }
            .take(MAX_SUGGESTIONS)
            .toList()
    }

    fun clear() = synchronized(lock) {
        values.clear()
        loaded = true
        if (file.baseFile.exists()) file.delete()
    }

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        if (!file.baseFile.exists()) return

        runCatching {
            val root = JSONObject(String(file.readFully(), StandardCharsets.UTF_8))
            val arr = root.optJSONArray("values") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val type = runCatching {
                    FieldAutofillType.valueOf(item.optString("type"))
                }.getOrNull() ?: continue
                val value = item.optString("value")
                if (value.isBlank()) continue
                values.add(
                    StoredFieldValue(
                        value = value,
                        type = type,
                        fieldKey = item.optString("fieldKey"),
                        packageName = item.optString("packageName"),
                        useCount = max(1, item.optInt("useCount", 1)),
                        lastUsed = item.optLong("lastUsed", 0L)
                    )
                )
            }
        }
    }

    private fun saveLocked() {
        val arr = JSONArray()
        values.forEach {
            arr.put(JSONObject().apply {
                put("value", it.value)
                put("type", it.type.name)
                put("fieldKey", it.fieldKey)
                put("packageName", it.packageName)
                put("useCount", it.useCount)
                put("lastUsed", it.lastUsed)
            })
        }
        val root = JSONObject().apply {
            put("version", 1)
            put("values", arr)
        }

        var stream = file.startWrite()
        try {
            stream.write(root.toString().toByteArray(StandardCharsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    private fun pruneLocked() {
        val byTypeAndField = values.groupBy { it.type to it.fieldKey }
        byTypeAndField.values.forEach { group ->
            group.sortedWith(compareByDescending<StoredFieldValue> { it.useCount }.thenByDescending { it.lastUsed })
                .drop(MAX_VALUES_PER_FIELD)
                .forEach(values::remove)
        }

        if (values.size > MAX_VALUES_TOTAL) {
            values.sortWith(compareByDescending<StoredFieldValue> { it.lastUsed }.thenByDescending { it.useCount })
            values.subList(MAX_VALUES_TOTAL, values.size).clear()
        }
    }

    companion object {
        private const val MAX_SUGGESTIONS = 3
        private const val MAX_VALUES_PER_FIELD = 5
        private const val MAX_VALUES_TOTAL = 500
    }
}
