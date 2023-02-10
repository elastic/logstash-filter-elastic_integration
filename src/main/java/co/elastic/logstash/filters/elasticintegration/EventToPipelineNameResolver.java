package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;
import co.elastic.logstash.filters.elasticintegration.resolver.UncacheableResolver;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * An {@code EventToPipelineNameResolver} is capable of determining the name of a
 * single target pipeline for an {@link Event}.
 *
 * <p>
 *     Implementations <em>MUST NOT</em> cache the events themselves,
 *     but <em>MAY</em> perform internal caching of low-cardinality things from the events.
 * </p>
 */
public interface EventToPipelineNameResolver extends UncacheableResolver<Event, String> {
    @Override
    Optional<String> resolve(Event event, Consumer<Exception> exceptionHandler);
}
