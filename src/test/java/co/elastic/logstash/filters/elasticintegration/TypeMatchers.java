package co.elastic.logstash.filters.elasticintegration;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;

public class TypeMatchers {
    public static <T> Matcher<T> instanceOfMatching(Class<?> expectedType, Matcher<? extends T> matcher) {
        return new IsInstanceMatching<>(expectedType, matcher);
    }

    public static class IsInstanceMatching<T> extends TypeSafeDiagnosingMatcher<T> {
        private final Class<?> expectedType;
        private final Matcher<? extends T> matcher;

        public IsInstanceMatching(Class<?> expectedType, Matcher<? extends T> matcher) {
            super(expectedType);
            this.expectedType = expectedType;
            this.matcher = matcher;
        }

        @Override
        protected boolean matchesSafely(T item, Description mismatchDescription) {
            if (!matcher.matches(item)) {
                matcher.describeMismatch(item, mismatchDescription);
                return false;
            }
            return true;
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("an instance of ").appendText(expectedType.getName())
                    .appendText(" that ").appendDescriptionOf(matcher);
        }
    }
}
