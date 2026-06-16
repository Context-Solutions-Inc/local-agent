package com.contextsolutions.localagent.i18n

/**
 * Hand-rolled positional substitutor for catalog templates. We can't use
 * `String.format` positional specifiers (`%1$s`) — they're JVM-only and
 * `:shared` has an `iosMain` set — so this implements just the slice we need:
 *
 *  - `%<n>$s` / `%<n>$d` — substitute the n-th argument (1-based). `s` and `d`
 *    are treated identically (both `toString()` the argument); the distinction
 *    is kept only as authoring documentation in the templates.
 *  - `%%` — a literal `%`.
 *
 * Out-of-range indices and malformed specifiers are emitted verbatim rather
 * than throwing — a localization template must never crash the app.
 */
internal fun formatPositional(template: String, args: Array<out Any?>): String {
    if (template.indexOf('%') < 0) return template
    val out = StringBuilder(template.length + 16)
    var i = 0
    val n = template.length
    while (i < n) {
        val c = template[i]
        if (c != '%') {
            out.append(c)
            i++
            continue
        }
        // c == '%'. Look at what follows.
        if (i + 1 < n && template[i + 1] == '%') {
            out.append('%')
            i += 2
            continue
        }
        // Parse %<digits>$[sd]
        var j = i + 1
        var num = 0
        var sawDigit = false
        while (j < n && template[j].isDigit()) {
            num = num * 10 + (template[j] - '0')
            sawDigit = true
            j++
        }
        if (sawDigit && j + 1 < n && template[j] == '$' &&
            (template[j + 1] == 's' || template[j + 1] == 'd')
        ) {
            val idx = num - 1
            if (idx in args.indices) {
                out.append(args[idx]?.toString() ?: "null")
            } else {
                // Out of range: keep the specifier verbatim for visibility.
                out.append(template, i, j + 2)
            }
            i = j + 2
        } else {
            // Not a recognised specifier — emit the '%' and move on.
            out.append('%')
            i++
        }
    }
    return out.toString()
}
