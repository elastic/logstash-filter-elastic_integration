package co.elastic.logstash.filters.elasticintegration.util;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class ExceptionsTest {
    @Test
    void messageFromUnwrappedIsIncludedInWrapped() {
        final Exception unwrapped = new IllegalStateException("the actual cause");
        final RuntimeException wrapped = Exceptions.wrap(unwrapped, "a descriptive reason");

        assertThat(wrapped.getCause(), is(sameInstance(unwrapped)));
        assertThat(wrapped.getMessage(), is(stringContainsInOrder("a descriptive reason", "the actual cause")));
    }
}