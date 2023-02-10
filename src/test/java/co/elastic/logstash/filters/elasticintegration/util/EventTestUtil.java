package co.elastic.logstash.filters.elasticintegration.util;

import co.elastic.logstash.api.Event;
import org.logstash.plugins.BasicEventFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class EventTestUtil {

    public static Event eventFromMap(final Map<String, Object> map) {
        return BasicEventFactory.INSTANCE.newEvent(map);
    }

    public static Map<String,Object> mapWithNullValue(final String key) {
        final Map<String, Object> intermediate = new HashMap<>();
        intermediate.put(key, null);
        return Collections.unmodifiableMap(intermediate);
    }
}
