package com.example.subhanmishra.service.parse;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * Counts tokens the way {@code TokenTextSplitter} does.
 * <p>
 * The same encoding as the splitter ({@code CL100K_BASE}), so the budget counted while joining paragraphs
 * is the budget the splitter enforces. Counting characters instead would not work: characters per token
 * varies too much. The encoding is stateless, so one instance is safe on every thread.
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
