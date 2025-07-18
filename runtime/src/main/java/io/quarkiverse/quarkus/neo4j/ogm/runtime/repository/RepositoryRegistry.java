package io.quarkiverse.quarkus.neo4j.ogm.runtime.repository;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class RepositoryRegistry {

    private final Map<Class<?>, Repository<?>> registry = new ConcurrentHashMap<>();

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
