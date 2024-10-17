package co.elastic.logstash.filters.elasticintegration.util;

import org.elasticsearch.ingest.IngestDocument;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class IngestDocumentUtil {
    private IngestDocumentUtil() {
    }

    private static final Map<String, Object> BASE_SOURCE_AND_METADATA = Map.of(IngestDocument.Metadata.VERSION.getFieldName(), 1L);

    public static IngestDocument createIngestDocument(Map<String, Object> data) {
        final Map<String, Object> merged_source_and_metadata = new HashMap<>(BASE_SOURCE_AND_METADATA);
        merged_source_and_metadata.putAll(data);

        return new IngestDocument(merged_source_and_metadata, Map.of("timestamp", Instant.now().toString()));
    }
}