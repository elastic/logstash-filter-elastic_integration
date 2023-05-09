package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;
import co.elastic.logstash.filters.elasticintegration.util.TestCapturingLogger;
import com.github.seregamorph.hamcrest.OptionalMatchers;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.logstash.plugins.BasicEventFactory;

import java.util.concurrent.atomic.AtomicReference;

import static co.elastic.logstash.filters.elasticintegration.util.TestCapturingLogger.hasLogEntry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class SprintfTemplateEventToPipelineNameResolverTest {

    private final TestCapturingLogger logger = new TestCapturingLogger();

    @Test
    void sprintfConstant() {
        final EventToPipelineNameResolver e2pnr = SprintfTemplateEventToPipelineNameResolver.from("always");

        final AtomicReference<Exception> lastException = new AtomicReference<>();
        assertThat(e2pnr.resolve(BasicEventFactory.INSTANCE.newEvent(), lastException::set), is(OptionalMatchers.isPresent(equalTo("always"))));
    }

    @Test
    void sprintfFullyResolved() {
        final SprintfTemplateEventToPipelineNameResolver e2pnr = new SprintfTemplateEventToPipelineNameResolver("this-%{that}-%{[another][thing]}");

        final Event event = BasicEventFactory.INSTANCE.newEvent();
        event.setField("that", "TTHHAATT");
        event.setField("[another][thing]", "thang");

        final AtomicReference<Exception> lastException = new AtomicReference<>();
        assertThat(e2pnr.resolve(event, lastException::set), is(OptionalMatchers.isPresent(equalTo("this-TTHHAATT-thang"))));
    }

    @Test
    void sprintfPartiallyResolved() {
        final SprintfTemplateEventToPipelineNameResolver e2pnr = new SprintfTemplateEventToPipelineNameResolver("this-%{that}-%{[another][thing]}", logger);

        final Event event = BasicEventFactory.INSTANCE.newEvent();
        event.setField("that", "TTHHAATT");

        final AtomicReference<Exception> lastException = new AtomicReference<>();
        assertThat(e2pnr.resolve(event, lastException::set), is(OptionalMatchers.isEmpty()));

        assertThat(logger, hasLogEntry(Level.TRACE, stringContainsInOrder("template", "failed to resolve", "[another][thing]")));
    }
}