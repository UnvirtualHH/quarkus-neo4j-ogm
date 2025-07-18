package io.quarkiverse.quarkus.neo4j.ogm.runtime.repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ReactiveRepositoryRegistry {

    private final Map<Class<?>, ReactiveRepository<?>> registry = new ConcurrentHashMap<>();

    public void register(Class<?> entityType, ReactiveRepository<?> repository) {
        if (!isRegistered(entityType)) {
            registry.put(entityType, repository);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> ReactiveRepository<T> getReactiveRepository(Class<T> entityType) {
        ReactiveRepository<?> repo = registry.get(entityType);
        if (repo == null) {
            throw new IllegalStateException("No reactive repository registered for type: " + entityType.getName());
        }
        return (ReactiveRepository<T>) repo;
    }

    public boolean isRegistered(Class<?> type) {
        return registry.containsKey(type);
    }
}
