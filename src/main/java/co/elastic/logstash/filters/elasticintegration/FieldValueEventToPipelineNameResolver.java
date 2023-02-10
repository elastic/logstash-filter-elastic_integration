package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;

import java.util.Optional;
import java.util.function.Consumer;

import static co.elastic.logstash.filters.elasticintegration.util.EventUtil.ensureValidFieldReference;
import static co.elastic.logstash.filters.elasticintegration.util.EventUtil.safeExtractString;

public class FieldValueEventToPipelineNameResolver implements EventToPipelineNameResolver {
    private final String fieldReference;

    public FieldValueEventToPipelineNameResolver(String fieldReference) {
        this.fieldReference = ensureValidFieldReference(fieldReference, "pipeline name");
    }

    @Override
    public Optional<String> resolve(Event event, Consumer<Exception> exceptionHandler) {
        return Optional.ofNullable(safeExtractString(event, fieldReference));
    }
}
