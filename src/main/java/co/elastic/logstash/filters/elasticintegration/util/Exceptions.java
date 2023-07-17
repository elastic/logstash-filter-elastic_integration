package co.elastic.logstash.filters.elasticintegration.util;

import javax.annotation.Nonnull;

public class Exceptions {
    private Exceptions() {
        // util
    }

    public static RuntimeException wrap(final @Nonnull Exception unwrapped,
                                        final @Nonnull String wrapMessage) {
        return new RuntimeException(wrapMessage + " (" + unwrapped.getMessage() + ")", unwrapped);
    }

}
