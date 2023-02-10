package co.elastic.logstash.filters.elasticintegration.util;

import co.elastic.logstash.api.Event;
import org.logstash.FieldReference;

import java.util.Objects;

public class EventUtil {
    private EventUtil() {
    }

    public static String safeExtractString(final Event event, final String fieldReference) {
        return safeExtractValue(event, fieldReference, String.class);
    }

    static <T> T safeExtractValue(final Event event, final String fieldReference, final Class<T> tClass) {
        final Object fieldValue = event.getField(fieldReference);

        if (Objects.nonNull(fieldValue) && tClass.isAssignableFrom(fieldValue.getClass())) {
            return tClass.cast(fieldValue);
        }

        return null;
    }

    public static String ensureValidFieldReference(final String fieldReference, final String descriptor) {
        if (!FieldReference.isValid(fieldReference)) {
            throw new IllegalArgumentException(String.format("Invalid field reference for `%s`: `%s`", descriptor, fieldReference));
        }
        return fieldReference;
    }
}
