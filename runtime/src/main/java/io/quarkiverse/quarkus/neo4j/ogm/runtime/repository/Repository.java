package io.quarkiverse.quarkus.neo4j.ogm.runtime.repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import org.neo4j.driver.*;
import org.neo4j.driver.Record;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.EntityMapper;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.EntityWithRelations;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.RelationLoader;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.RelationshipData;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.repository.util.Pageable;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.repository.util.Paged;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.repository.util.Sortable;

public abstract class Repository<T> {
    // ThreadLocal to track visited entities and prevent circular references
    private static final ThreadLocal<Set<Object>> VISITED = ThreadLocal.withInitial(ConcurrentHashMap::newKeySet);

    protected final Driver driver;
    protected final String label;
    protected final EntityMapper<T> entityMapper;
    protected final RepositoryRegistry registry;
    protected final RelationLoader<T> relationLoader;

    public Repository() {
        this.driver = null;
        this.label = null;
        this.entityMapper = null;
        this.registry = null;
        this.relationLoader = null;
    }

    public Repository(Driver driver, String label, EntityMapper<T> entityMapper, RepositoryRegistry registry) {
        this.driver = driver;
        this.label = label;
        this.entityMapper = entityMapper;
        this.registry = registry;
        this.relationLoader = null;
    }

    public Repository(Driver driver, String label, EntityMapper<T> entityMapper, RepositoryRegistry registry,
            RelationLoader<T> relationLoader) {
        this.driver = driver;
        this.label = label;
        this.entityMapper = entityMapper;
        this.registry = registry;
        this.relationLoader = relationLoader;
    }

    protected abstract Class<T> getEntityType();

    /**
     * Get the relation loader for this repository
     *
     * @return The relation loader, or null if none is configured
     */
    public RelationLoader<T> getRelationLoader() {
        return relationLoader;
    }

    /**
     * Clear the visited entities set
     */
    private static void clearVisited() {
        VISITED.get().clear();
    }

    private <R> R inWriteTx(Function<Transaction, R> work) {
        try (Session session = driver.session()) {
            try (Transaction tx = session.beginTransaction()) {
                R result = work.apply(tx);
                tx.commit();
                return result;
            }
        }
    }

    private void inWriteTxVoid(Consumer<Transaction> work) {
        try (Session session = driver.session()) {
            try (Transaction tx = session.beginTransaction()) {
                work.accept(tx);
                tx.commit();
            }
        }
    }

    private <R> R inReadTx(Function<Transaction, R> work) {
        try (Session session = driver.session()) {
            try (Transaction tx = session.beginTransaction()) {
                return work.apply(tx);
            }
        }
    }

    public T findById(Object id) {
        return inReadTx(tx -> {
            var result = tx.run("MATCH (n:" + label + " {id: $id}) RETURN n AS node",
                    Values.parameters("id", id));
            T entity = entityMapper.map(result.single(), "node");

            loadRelations(entity);
            clearVisited();
            return entity;
        });
    }

    public List<T> findAll() {
        return inReadTx(tx -> {
            var result = tx.run("MATCH (n:" + label + ") RETURN n AS node");
            List<T> entities = result.list(r -> entityMapper.map(r, "node"));
            entities.forEach(entity -> loadRelations(entity));
            clearVisited();
            return entities;
        });
    }

    public List<T> findAll(Pageable pageable, Sortable sortable) {
        return inReadTx(tx -> {
            String sortClause = (sortable != null) ? sortable.toCypher("n") : "";
            String cypher = String.format("MATCH (n:%s) RETURN n AS node %s SKIP $skip LIMIT $limit", label, sortClause);
            Map<String, Object> params = Map.of(
                    "skip", pageable.page() * pageable.size(),
                    "limit", pageable.size());
            var result = tx.run(cypher, params);
            List<T> entities = result.list(r -> entityMapper.map(r, "node"));
            entities.forEach(this::loadRelations);
            clearVisited();
            return entities;
        });
    }

    public Paged<T> findAllPaged(Pageable pageable, Sortable sortable) {
        String sortClause = (sortable != null) ? sortable.toCypher("n") : "";
        String cypher = String.format("MATCH (n:%s) RETURN n AS node %s SKIP $skip LIMIT $limit", label, sortClause);
        Map<String, Object> params = Map.of(
                "skip", pageable.page() * pageable.size(),
                "limit", pageable.size());

        List<T> content = inReadTx(tx -> {
            var result = tx.run(cypher, params);
            List<T> entities = result.list(r -> entityMapper.map(r, "node"));
            entities.forEach(this::loadRelations);
            clearVisited();
            return entities;
        });

        long total = count();

        return new Paged<>(content, total, pageable.page(), pageable.size());
    }

    public long count() {
        return inReadTx(tx -> {
            var result = tx.run("MATCH (n:" + label + ") RETURN count(n) AS count");
            return result.single().get("count").asLong();
        });
    }

    public T create(T entity) {
        return inWriteTx(tx -> {
            EntityWithRelations data = entityMapper.toDb(entity);
            Object id = entityMapper.getNodeId(entity);

            var result = tx.run("CREATE (n:" + label + " $props) RETURN n AS node",
                    Values.parameters("props", data.getProperties()));
            T saved = entityMapper.map(result.single(), "node");

            persistRelationships(tx, label, id, data.getRelationships());
            clearVisited();
            return saved;
        });
    }

    public T update(T entity) {
        Object id = entityMapper.getNodeId(entity);
        if (id == null)
            throw new IllegalArgumentException("Entity ID cannot be null");

        return inWriteTx(tx -> {
            EntityWithRelations data = entityMapper.toDb(entity);

            var result = tx.run("MATCH (n:" + label + " {id: $id}) SET n += $props RETURN n AS node",
                    Values.parameters("id", id, "props", data.getProperties()));
            T updated = entityMapper.map(result.single(), "node");

            persistRelationships(tx, label, id, data.getRelationships());
            clearVisited();
            return updated;
        });
    }

    public T merge(T entity) {
        Object id = entityMapper.getNodeId(entity);
        if (id == null)
            throw new IllegalArgumentException("Entity ID cannot be null");

        return inWriteTx(tx -> {
            EntityWithRelations data = entityMapper.toDb(entity);

            var result = tx.run("MERGE (n:" + label + " {id: $id}) SET n += $props RETURN n AS node",
                    Values.parameters("id", id, "props", data.getProperties()));
            T merged = entityMapper.map(result.single(), "node");

            persistRelationships(tx, label, id, data.getRelationships());
            clearVisited();
            return merged;
        });
    }

    public void delete(T entity) {
        deleteById(entityMapper.getNodeId(entity));
    }

    public void deleteById(Object id) {
        if (id == null)
            throw new IllegalArgumentException("ID cannot be null");

        inWriteTxVoid(tx -> {
            tx.run("MATCH (n:" + label + " {id: $id}) DELETE n", Values.parameters("id", id)).consume();
        });
    }

    public boolean existsById(Object id) {
        return inReadTx(tx -> {
            var result = tx.run("MATCH (n:" + label + " {id: $id}) RETURN count(n) > 0 AS exists",
                    Values.parameters("id", id));
            return result.single().get("exists").asBoolean();
        });
    }

    public boolean exists(T entity) {
        return existsById(entityMapper.getNodeId(entity));
    }

    public List<T> query(String cypher) {
        return query(cypher, Map.of());
    }

    public List<T> query(String cypher, Pageable pageable, Sortable sortable) {
        return query(cypher, Map.of(), pageable, sortable);
    }

    public List<T> query(String cypher, Map<String, Object> parameters) {
        return inReadTx(tx -> {
            List<T> results = tx.run(cypher, Values.value(parameters))
                    .list(r -> entityMapper.map(r, "node"));
            results.forEach(entity -> loadRelations(entity));
            clearVisited();
            return results;
        });
    }

    public List<T> query(String cypher, Map<String, Object> parameters, Pageable pageable, Sortable sortable) {
        return inReadTx(tx -> {
            String sortClause = (sortable != null) ? sortable.toCypher("n") : "";
            String pagedCypher = String.format("%s %s SKIP $skip LIMIT $limit", cypher, sortClause);
            Map<String, Object> params = new HashMap<>(parameters);
            params.put("skip", pageable.page() * pageable.size());
            params.put("limit", pageable.size());
            var result = tx.run(pagedCypher, params);
            List<T> entities = result.list(r -> entityMapper.map(r, "node"));
            entities.forEach(this::loadRelations);
            clearVisited();
            return entities;
        });
    }

    public Paged<T> queryPaged(
            String baseCypher,
            Map<String, Object> parameters,
            Pageable pageable,
            Sortable sortable) {
        String sortClause = (sortable != null) ? sortable.toCypher("n") : "";
        String pagedCypher = baseCypher + " RETURN n AS node " + sortClause + " SKIP $skip LIMIT $limit";
        Map<String, Object> allParams = new HashMap<>(parameters);
        allParams.put("skip", pageable.page() * pageable.size());
        allParams.put("limit", pageable.size());

        List<T> content = inReadTx(tx -> {
            var result = tx.run(pagedCypher, allParams);
            List<T> entities = result.list(r -> entityMapper.map(r, "node"));
            entities.forEach(this::loadRelations);
            return entities;
        });

        String countCypher = baseCypher + " RETURN count(n) AS count";
        long total = inReadTx(tx -> {
            var result = tx.run(countCypher, parameters);
            return result.single().get("count").asLong();
        });

        clearVisited();
        return new Paged<>(content, total, pageable.page(), pageable.size());
    }

    public T querySingle(String cypher) {
        return querySingle(cypher, Map.of());
    }

    public T querySingle(String cypher, Map<String, Object> parameters) {
        return inReadTx(tx -> {
            var result = tx.run(cypher, Values.value(parameters));
            if (!result.hasNext()) {
                return null;
            }
            T entity = entityMapper.map(result.single(), "node");
            loadRelations(entity);
            clearVisited();
            return entity;
        });
    }

    public void execute(String cypher, Map<String, Object> parameters) {
        inWriteTxVoid(tx -> tx.run(cypher, Values.value(parameters)).consume());
    }

    public <R> R queryScalar(String cypher, Function<Record, R> mapper) {
        return queryScalar(cypher, Map.of(), mapper);
    }

    public <R> R queryScalar(String cypher, Map<String, Object> parameters, Function<Record, R> mapper) {
        return inReadTx(tx -> mapper.apply(tx.run(cypher, Values.value(parameters)).single()));
    }

    private void persistRelationships(Transaction tx, String label, Object fromId, List<RelationshipData> relationships) {
        if (relationships == null || relationships.isEmpty())
            return;

        for (RelationshipData rel : relationships) {
            Object toId = rel.getTargetId();

            if (toId == null && rel.getTargetEntity() != null) {
                Object target = rel.getTargetEntity();
                Class<?> targetType = target.getClass();

                Repository<Object> targetRepo = (Repository<Object>) registry.getRepository(targetType);
                Object id = targetRepo.getEntityMapper().getNodeId(target);
                if (id == null) {
                    Object merged = targetRepo.create(target);
                    id = targetRepo.getEntityMapper().getNodeId(merged);
                }
                rel.setTargetId(id);
                toId = id;
            }

            if (toId == null)
                continue;

            String query = switch (rel.getDirection()) {
                case OUTGOING -> "MATCH (a:" + label + " {id: $from}), (b {id: $to}) MERGE (a)-[r:" + rel.getType() + "]->(b)";
                case INCOMING -> "MATCH (a:" + label + " {id: $from}), (b {id: $to}) MERGE (a)<-[r:" + rel.getType() + "]-(b)";
                case BOTH -> throw new UnsupportedOperationException("Direction.BOTH is not supported yet");
                case UNDIRECTED -> throw new UnsupportedOperationException("Direction.UNDIRECTED is not supported yet");
            };

            tx.run(query, Values.parameters("from", fromId, "to", toId));
        }
    }

    public EntityMapper<T> getEntityMapper() {
        return entityMapper;
    }

    /**
     * Loads all relationships for the given entity with depth control.
     * Uses ThreadLocal visited set to prevent infinite loops.
     *
     * @param entity The entity to load relationships for
     * @param currentDepth The current traversal depth
     */
    protected void loadRelations(T entity, int currentDepth) {
        if (entity == null || relationLoader == null) {
            return;
        }

        Object id = entityMapper.getNodeId(entity);
        Set<Object> visited = VISITED.get();

        if (id == null || visited.contains(id)) {
            return;
        }

        visited.add(id);
        relationLoader.loadRelations(entity, currentDepth);
    }

    /**
     * Enhanced loadRelations method that calls the depth-aware version with depth 0
     */
    protected void loadRelations(T entity) {
        loadRelations(entity, 0);
        // Note: clearVisited() is called by the calling method to ensure proper cleanup
    }

    // Utility method for working with different entity types
    @SuppressWarnings("unchecked")
    private void loadRelationsForAnyEntity(Object entity, int currentDepth) {
        if (entity == null) {
            return;
        }

        Class<?> entityClass = entity.getClass();
        Repository<Object> repository = (Repository<Object>) registry.getRepository(entityClass);

        if (repository != null && repository.getRelationLoader() != null) {
            repository.getRelationLoader().loadRelations(entity, currentDepth);
        }
    }
}