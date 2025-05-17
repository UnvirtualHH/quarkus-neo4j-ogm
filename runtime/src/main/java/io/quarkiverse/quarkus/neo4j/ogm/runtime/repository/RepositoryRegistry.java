package io.quarkiverse.quarkus.neo4j.ogm.runtime.repository;

import java.util.HashMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class RepositoryRegistry {

    private final Map<Class<?>, Repository<?>> registry = new HashMap<>();

    public void register(Class<?> entityType, Repository<?> repository) {
        if (!isRegistered(entityType)) {
            registry.put(entityType, repository);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> Repository<T> getRepository(Class<T> entityType) {
        Repository<?> repo = registry.get(entityType);
        if (repo == null) {
            throw new IllegalStateException("No repository registered for type: " + entityType.getName());
        }
        return (Repository<T>) repo;
    }

    public boolean isRegistered(Class<?> type) {
        return registry.containsKey(type);
    }
}
