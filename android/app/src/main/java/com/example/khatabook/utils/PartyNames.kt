package com.example.khatabook.utils

import java.util.Locale

/**
 * The canonical form of a party (customer/supplier) name.
 *
 * Names arrive from three places that disagree about casing — typed entry,
 * the contacts picker, and Scan Khata's OCR — so "Bhavish", "bhavish" and
 * "BHAVISH" were all stored as distinct customers. Storing one canonical form
 * makes them a single record.
 *
 * UPPERCASE is the canonical form. It is applied only to the party *name*
 * field: notes, ledger descriptions, business names and transaction text are
 * deliberately untouched, because those are prose the user wrote and shouting
 * them back would be a regression, not a fix.
 *
 * Locale.ROOT, not the default locale: under a Turkish locale the default
 * uppercase of "i" is "İ", which would make the same name normalise
 * differently depending on device settings — and therefore stop matching
 * itself across devices or after a language change.
 */
object PartyNames {

    /**
     * Canonical storage form: trimmed, internal whitespace collapsed, uppercased.
     *
     * Whitespace collapsing matters for matching, not cosmetics — OCR
     * routinely emits "RAVI  KUMAR" with a double space, which would
     * otherwise never equal the typed "RAVI KUMAR".
     */
    fun normalize(raw: String): String =
        raw.trim().replace(WHITESPACE, " ").uppercase(Locale.ROOT)

    /**
     * Comparison key for deciding whether two names refer to the same party.
     *
     * Strips characters that carry no identity — punctuation, and the
     * separators OCR sprinkles in — while keeping letters *and digits*.
     * Digits are kept deliberately: "COUNTER 2" and "COUNTER 3" are different
     * shops, and dropping the number would silently merge them.
     */
    fun matchKey(raw: String): String =
        normalize(raw).filter { it.isLetterOrDigit() || it == ' ' }
            .replace(WHITESPACE, " ")
            .trim()

    private val WHITESPACE = Regex("\\s+")
}
