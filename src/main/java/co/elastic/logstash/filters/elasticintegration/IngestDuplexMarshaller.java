package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;
import co.elastic.logstash.api.EventFactory;
import org.elasticsearch.ingest.IngestDocument;
import org.logstash.Javafier;
import org.logstash.Valuefier;
import org.logstash.plugins.BasicEventFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * The {@code IngestDuplexMarshaller} is capable of marshalling events between the internal logstash {@link Event}
 * and the external Elasticsearch {@link IngestDocument}.
 */
public class IngestDuplexMarshaller {
    private static final String INGEST_TIMESTAMP = "timestamp";
    private final EventFactory eventFactory;

    private static final IngestDuplexMarshaller DEFAULT_INSTANCE = new IngestDuplexMarshaller(BasicEventFactory.INSTANCE);

    private IngestDuplexMarshaller(final EventFactory eventFactory) {
        this.eventFactory = eventFactory;
    }

    public static IngestDuplexMarshaller defaultInstance() {
        return DEFAULT_INSTANCE;
    }

    public IngestDocument toIngestDocument(final Event event) {
        Map<String, Object> data = new HashMap<>();
        Map<String, Object> ingestMetadata = new HashMap<>();

        // handle timestamp separately
        // TODO hack to work around Logstash's API package only exposing a millisecond timestamp
        ingestMetadata.put(INGEST_TIMESTAMP, ((org.logstash.Event) event).getTimestamp());

        // ensure events have a _version
        data.put(IngestDocument.Metadata.VERSION.getFieldName(), 1L);

        for (Map.Entry<String, Object> entry : event.getData().entrySet()) {
            if (!entry.getKey().equals(org.logstash.Event.TIMESTAMP)) {
                data.put(entry.getKey(), Javafier.deep(entry.getValue()));
            }
        }

        final Map<String,Object> metadata = new HashMap<>();
        for (Map.Entry<String, Object> entry : event.getMetadata().entrySet()) {
            metadata.put(entry.getKey(), Javafier.deep(entry.getValue()));
        }
        data.put("@metadata", metadata);

        return new IngestDocument(data, ingestMetadata);
    }

    public Event toLogstashEvent(final IngestDocument document) {
        Map<String, Object> source = document.getSourceAndMetadata();
        Map<String, Object> metadata = document.getIngestMetadata();

        final Event e = eventFactory.newEvent();

        // handle timestamp separately
        Object z = metadata.get(INGEST_TIMESTAMP);
        if (z != null) {
            // TODO hack to work around Logstash's API package only exposing a millisecond timestamp
            ((org.logstash.Event) e).setTimestamp((org.logstash.Timestamp) z);
        }

        // handle version separately
        Long version = (Long) source.get(IngestDocument.Metadata.VERSION.getFieldName());
        if (version != null) {
            e.getData().put(org.logstash.Event.VERSION, String.valueOf(version));
        }

        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();

            if ((!key.equals(org.logstash.Event.VERSION)) &&
                    (!key.equals(IngestDocument.Metadata.VERSION.getFieldName()))) {
                e.setField(entry.getKey(), Valuefier.convert(entry.getValue()));
            }
        }

        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (!entry.getKey().equals(INGEST_TIMESTAMP)) {
                e.setField(entry.getKey(), Valuefier.convert(entry.getValue()));
            }
        }

        return e;
    }
}
