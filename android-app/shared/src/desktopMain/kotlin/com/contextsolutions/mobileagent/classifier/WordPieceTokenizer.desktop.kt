package com.contextsolutions.mobileagent.classifier

import java.text.Normalizer

internal actual fun unicodeNormalizeNfd(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
