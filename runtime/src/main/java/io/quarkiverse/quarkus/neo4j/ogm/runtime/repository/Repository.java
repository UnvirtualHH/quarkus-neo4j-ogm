package io.quarkiverse.quarkus.neo4j.ogm.runtime.repository;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

import org.neo4j.driver.*;
import org.neo4j.driver.Record;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.EntityMapper;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.EntityWithRelations;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.RelationLoader;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.RelationshipData;

public abstract class Repository<T> {
    private final Set<Object> visited = new HashSet<>();
    protected final Driver driver;
    protected final String label;
    protected final EntityMapper<T> entityMapper;
    protected final RepositoryRegistry registry;
    protected RelationLoader<T> relationLoader;

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
        this.relationLoader = null; // Will be set by the generated repository
    }

    protected abstract Class<T> getEntityType();

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
            return entity;
        });
    }

    public List<T> findAll() {
        return inReadTx(tx -> {
            var result = tx.run("MATCH (n:" + label + ") RETURN n AS node");
            List<T> entities = result.list(r -> entityMapper.map(r, "node"));
            entities.forEach(entity -> loadRelations(entity));
            return entities;
        });
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

    public List<T> query(String cypher, Map<String, Object> parameters) {
        return inReadTx(tx -> {
            List<T> results = tx.run(cypher, Values.value(parameters))
                    .list(r -> entityMapper.map(r, "node"));
            results.forEach(entity -> loadRelations(entity));
            return results;
        });
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
     * Loads all relationships for the given entity.
     *
     * @param entity The entity to load relationships for
     */
    protected void loadRelations(T entity) {
        if (entity == null || relationLoader == null) {
            return;
        }

        Object id = entityMapper.getNodeId(entity);
        if (id == null || visited.contains(id)) {
            return;
        }

        visited.add(id);

        try {
            relationLoader.loadRelations(entity);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load relationships for entity: " + entity, e);
        }
    }
}