package co.elastic.logstash.filters.elasticintegration.util;

/**
 * A {@code DuplexMarshaller} is a two-way transformer of an object between an internal and an external form.
 * @param <I> the internal type
 * @param <E> the external type
 */
public interface DuplexMarshaller<I, E> {
    E toExternal(I internal);
    I toInternal(E external);
}