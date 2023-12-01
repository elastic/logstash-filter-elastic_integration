/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;
import co.elastic.logstash.filters.elasticintegration.resolver.AbstractSimpleResolver;
import com.google.common.base.Strings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static co.elastic.logstash.filters.elasticintegration.util.EventUtil.serializeEventForLog;

public class SprintfTemplateEventToPipelineNameResolver extends AbstractSimpleResolver<Event, String> implements EventToPipelineNameResolver {

    private static final Logger DEFAULT_LOGGER = LogManager.getLogger(SprintfTemplateEventToPipelineNameResolver.class);

    private final String template;

    private final Logger logger;

    private static final String TEMPLATE_REFERENCE_START = "%{";

    public static EventToPipelineNameResolver from(final String template) {
        if (template.contains(TEMPLATE_REFERENCE_START)) {
            return new SprintfTemplateEventToPipelineNameResolver(template);
        }
        final Optional<String> constantReturn = Optional.of(template);
        return ((event, exceptionHandler) -> constantReturn);
    }

    SprintfTemplateEventToPipelineNameResolver(final String template) {
        this(template, DEFAULT_LOGGER);
    }

    SprintfTemplateEventToPipelineNameResolver(final String template,
                                               final Logger logger) {
        this.template = template;
        this.logger = logger;
    }

    @Override
    public Optional<String> resolveSafely(Event resolveKey) throws Exception {
        final String result = resolveKey.sprintf(this.template);

        if (result.contains(TEMPLATE_REFERENCE_START)) {
            logger.trace(() -> String.format("sprintf template `%s` failed to resolve one or more fields `%s` in %s", this.template, result, serializeEventForLog(logger, resolveKey)));
            return Optional.empty();
        }

        return Optional.of(result);
    }
}
