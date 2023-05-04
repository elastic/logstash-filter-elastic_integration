package co.elastic.logstash.filters.elasticintegration.util;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.spi.AbstractLogger;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

public class TestCapturingLogger extends AbstractLogger {
    private final Collection<LogEntry> messagesReceived = new ConcurrentLinkedQueue<>();

    @Override
    public void logMessage(String fqcn, Level level, Marker marker, Message message, Throwable t) {
        final String stringMessage = (Objects.isNull(message) ? null : message.getFormattedMessage());
        this.messagesReceived.add(new LogEntry(level, stringMessage, t));
    }

    public record LogEntry(Level level, String  message, Throwable t) {};

    public static LogEntryMatcher hasLogEntry(Matcher<Level> levelMatcher, Matcher<String> messageMatcher) {
        return new LogEntryMatcher(levelMatcher, messageMatcher);
    }
    public static LogEntryMatcher hasLogEntry(final Level level, Matcher<String> messageMatcher) {
        return hasLogEntry(Matchers.equalTo(level), messageMatcher);
    }

    static class LogEntryMatcher extends TypeSafeDiagnosingMatcher<TestCapturingLogger> {
        private final Matcher<Level> levelMatcher;
        private final Matcher<String> messageMatcher;

        private LogEntryMatcher(Matcher<Level> levelMatcher, Matcher<String> messageMatcher) {
            this.levelMatcher = levelMatcher;
            this.messageMatcher = messageMatcher;
        }

        @Override
        protected boolean matchesSafely(TestCapturingLogger testCapturingLogger, Description mismatchDescription) {
            for (LogEntry logEntry : testCapturingLogger.messagesReceived) {
                if (levelMatcher.matches(logEntry.level) && messageMatcher.matches(logEntry.message)) {
                    return true;
                }
            }

            mismatchDescription.appendText("mismatches were: ").appendValue(testCapturingLogger.messagesReceived);
            return false;
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("contain LogEntry with level ")
                    .appendDescriptionOf(this.levelMatcher)
                    .appendText("and message ")
                    .appendDescriptionOf(this.messageMatcher);
        }
    }

    // Boilerplate to satisfy org.apache.logging.log4j.spi.ExtendedLogger
    // that is missing from org.apache.logging.log4j.spi.AbstractLogger

    @Override public Level getLevel() { return Level.ALL; }
    @Override public boolean isEnabled(Level level, Marker marker, Message message, Throwable t) { return true; }
    @Override public boolean isEnabled(Level level, Marker marker, CharSequence message, Throwable t) { return true; }
    @Override public boolean isEnabled(Level level, Marker marker, Object message, Throwable t) { return true; }
    @Override public boolean isEnabled(Level level, Marker marker, String message, Throwable t) { return true; }
    @Override public boolean isEnabled(Level level, Marker marker, String message) { return true; }
    @Override public boolean isEnabled(Level level, Marker marker, String message, Object... params) { return true; }
    @Override public boolean isEnabled(Level level, Marker marker, String message, Object p0) { return true; }
    @Override public boolean isEnabled(Level level, Marker marker, String message, Object p0, Object p1) { return true; }
    @Override public boolean isEnabled(Level level, Marker marker, String message, Object p0, Object p1, Object p2) { return true; }
    @Override public boolean isEnabled(Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3) { return true; }
    @Override public boolean isEnabled(Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4) { return true; }
    @Override public boolean isEnabled(Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5) { return true; }
    @Override public boolean isEnabled(Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6) { return true; }
    @Override public boolean isEnabled(Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) { return true; }
    @Override public boolean isEnabled(Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) { return true; }
    @Override public boolean isEnabled(Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9) { return true; }

}
