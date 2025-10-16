package de.prgrm.quarkus.neo4j.ogm.runtime.repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.RelationLoader;

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

    /**
     * Direct access to the RelationLoader for an entity type, if available.
     */
    @SuppressWarnings("unchecked")
    public <T> RelationLoader<T> getLoader(Class<T> entityType) {
        Repository<?> repo = registry.get(entityType);
        if (repo == null) {
            throw new IllegalStateException("No repository registered for type: " + entityType.getName());
        }
        return (RelationLoader<T>) repo.getRelationLoader();
    }
}
