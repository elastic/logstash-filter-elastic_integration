/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.hamcrest.TypeSafeMatcher;

import java.util.Collection;
import java.util.Optional;

import static org.hamcrest.Matchers.anything;

class EventMatchers {
    static IsEventTagged isTagged(final String expectedTag) {
        return new IsEventTagged(expectedTag);
    }

    static <T> IsEventIncludingField<T> includesField(final String fieldReference) {
        return new IsEventIncludingField<>(fieldReference);
    }

    static IsEventExcludingField excludesField(final String fieldReference) {
        return new IsEventExcludingField(fieldReference);
    }

    static class IsEventTagged extends TypeSafeMatcher<Event> {
        private final String expectedTag;

        public IsEventTagged(String expectedTag) {
            this.expectedTag = expectedTag;
        }

        @Override
        protected boolean matchesSafely(Event event) {
            return Optional.ofNullable(event.getField("tags"))
                    .filter(Collection.class::isInstance).map(Collection.class::cast)
                    .filter(tagsCollection -> tagsCollection.contains(expectedTag))
                    .isPresent();
        }

        @Override
        public void describeTo(Description description) {
            description.appendText(String.format("include tag `%s`", expectedTag));
        }

        @Override
        protected void describeMismatchSafely(Event event, Description mismatchDescription) {
            if (!event.includes("tags")) {
                mismatchDescription.appendText("does not include tags");
            } else {
                mismatchDescription.appendText("had tags").appendValue(event.getField("tags"));
            }
        }
    }

    public static class IsEventExcludingField extends TypeSafeDiagnosingMatcher<Event> {
        private final String fieldReference;

        public IsEventExcludingField(String fieldReference) {
            this.fieldReference = fieldReference;
        }

        @Override
        protected boolean matchesSafely(Event event, Description mismatchDescription) {
            if (event.includes(this.fieldReference)) {
                mismatchDescription.appendText(String.format("event contained a value for `%s`", this.fieldReference))
                        .appendText("(").appendValue(event.getField(this.fieldReference)).appendText(")");
                return false;
            }
            return true;
        }

        @Override
        public void describeTo(Description description) {
            description.appendText(String.format("have not field `%s`", this.fieldReference));
        }
    }

    public static class IsEventIncludingField<V> extends TypeSafeDiagnosingMatcher<Event> {
        private final String fieldReference;
        private final Matcher<? super V> valueMatcher;

        public IsEventIncludingField(String fieldReference, Matcher<? super V> valueMatcher) {
            this.fieldReference = fieldReference;
            this.valueMatcher = valueMatcher;
        }

        public IsEventIncludingField(String fieldReference) {
            this(fieldReference, anything());
        }

        public <VV> IsEventIncludingField<VV> withValue(Matcher<? super VV> valueMatcher) {
            return new IsEventIncludingField<>(this.fieldReference, valueMatcher);
        }

        @Override
        protected boolean matchesSafely(Event event, Description mismatchDescription) {
            if (!event.includes(this.fieldReference)) {
                mismatchDescription.appendText("event did not contain a value for").appendValue(fieldReference);
                return false;
            }
            final Object fieldValue = event.getField(this.fieldReference);
            if (!valueMatcher.matches(fieldValue)) {
                valueMatcher.describeMismatch(fieldValue, mismatchDescription);
                return false;
            }

            return true;
        }

        @Override
        public void describeTo(Description description) {
            description.appendText(String.format("have field `%s`", this.fieldReference))
                    .appendText("->")
                    .appendDescriptionOf(valueMatcher);
        }
    }
}
