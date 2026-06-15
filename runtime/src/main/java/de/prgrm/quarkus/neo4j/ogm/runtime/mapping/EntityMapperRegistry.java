package de.prgrm.quarkus.neo4j.ogm.runtime.mapping;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class EntityMapperRegistry {

    private final Map<Class<?>, EntityMapper<?>> registry = new ConcurrentHashMap<>();

    public <T> void registerSelf(Class<T> type, EntityMapper<T> mapper) {
        registry.put(type, mapper);
    }

    @SuppressWarnings("unchecked")
    public <T> EntityMapper<T> get(Class<T> type) {
        EntityMapper<?> mapper = registry.get(type);
        if (mapper == null) {
            throw new IllegalStateException("No mapper registered for " + type);
        }
        return (EntityMapper<T>) mapper;
    }

    /**
     * Returns the mapper registered for the given type, or {@code null} if none is registered.
     * Unlike {@link #get(Class)} this never throws – useful for best-effort lookups (e.g. extracting
     * an id for cycle detection) where a missing mapper is tolerable.
     */
    @SuppressWarnings("unchecked")
    public <T> EntityMapper<T> find(Class<T> type) {
        return (EntityMapper<T>) registry.get(type);
    }
}
