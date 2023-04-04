package co.elastic.logstash.filters.elasticintegration;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.Temporal;

public class TemporalMatchers {

    static <T extends Temporal> Matcher<T> recentCurrentTimestamp() {
        return recentCurrentTimestamp(Duration.ofMillis(100));
    }

    static <T extends Temporal> Matcher<T> recentCurrentTimestamp(final Duration maxAge) {
        return new TypeSafeDiagnosingMatcher<>() {

            @Override
            protected boolean matchesSafely(final T timestamp, final Description mismatchDescription) {
                final Instant now = Instant.now();
                final Duration actualDelta = Duration.between(Instant.from(timestamp), now);
                if (actualDelta.isNegative()) {
                    mismatchDescription.appendText("value was in the future");
                    return false;
                }
                if (maxAge.minus(actualDelta).isNegative()) {
                    mismatchDescription.appendText("value was too old").appendText("(").appendValue(actualDelta).appendText(")");
                    return false;
                }
                return true;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("be less than ").appendValue(maxAge).appendText("in the past");
            }
        };
    }
}
