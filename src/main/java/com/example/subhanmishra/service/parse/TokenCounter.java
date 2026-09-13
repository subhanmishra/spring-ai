package com.example.subhanmishra.service.parse;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * Counts tokens the way {@code TokenTextSplitter} does.
 * <p>
 * Deliberately {@code CL100K_BASE}, the same as {@code TokenTextSplitter.DEFAULT_ENCODING_TYPE}, so the
 * budget counted while chunking and the budget the splitter enforces cannot drift apart. A character-count
 * approximation is not a substitute: PDF tables are space-padded, so characters per token varies wildly.
 * <p>
 * jtokkit encodings are stateless, so the shared instance is safe across the threads of
 * {@code documentProcessingPool}.
 */
public final class TokenCounter {

    private static final Encoding ENCODING =
            Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    private TokenCounter() {
    }

    public static int count(String text) {
        return ENCODING.countTokens(text);
    }
}
