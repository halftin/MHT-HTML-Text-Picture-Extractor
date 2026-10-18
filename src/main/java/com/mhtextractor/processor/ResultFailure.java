package com.mhtextractor.processor;

import com.mhtextractor.i18n.ExtractionTexts;
import java.util.Map;

/** Internal failure with a neutral presentation payload; the exception message remains diagnostic. */
final class ResultFailure extends RuntimeException {
    final String key;
    final Map<String, String> params;
    ResultFailure(String key, Map<String, String> params) {
        super(ExtractionTexts.message(key, params));
        this.key = key;
        this.params = Map.copyOf(params);
    }
}
