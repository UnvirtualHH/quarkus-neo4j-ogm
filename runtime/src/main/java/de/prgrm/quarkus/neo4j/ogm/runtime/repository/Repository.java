package de.prgrm.quarkus.neo4j.ogm.runtime.repository;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

import org.neo4j.driver.*;
import org.neo4j.driver.Record;

import de.prgrm.quarkus.neo4j.ogm.runtime.enums.RelationshipMode;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.EntityMapper;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.EntityWithRelations;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.RelationLoader;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.RelationshipData;
import de.prgrm.quarkus.neo4j.ogm.runtime.repository.errors.Neo4jExceptionTranslator;
import de.prgrm.quarkus.neo4j.ogm.runtime.repository.errors.NotFoundRepositoryException;
import de.prgrm.quarkus.neo4j.ogm.runtime.repository.util.Filter;
import de.prgrm.quarkus.neo4j.ogm.runtime.repository.util.Pageable;
import de.prgrm.quarkus.neo4j.ogm.runtime.repository.util.Paged;
import de.prgrm.quarkus.neo4j.ogm.runtime.repository.util.Sortable;
import de.prgrm.quarkus.neo4j.ogm.runtime.tx.TransactionManager;

public abstract class Repository<T> {

    protected final Driver driver;
    protected final String label;
    protected final EntityMapper<T> entityMapper;
    protected final RepositoryRegistry registry;
    protected final RelationLoader<T> relationLoader;
    protected final RelationVisitor relationVisitor;
    protected final TransactionManager txManager;

    public Repository() {
        this.driver = null;
        this.label = null;
        this.entityMapper = null;
        this.registry = null;
        this.relationLoader = null;
        this.relationVisitor = null;
        this.txManager = null;
    }

    public Repository(Driver driver, String label, EntityMapper<T> entityMapper, RepositoryRegistry registry,
            TransactionManager txManager) {
        this.driver = driver;
        this.label = label;
        this.entityMapper = entityMapper;
        this.registry = registry;
        this.relationLoader = null;
        this.relationVisitor = null;
        this.txManager = txManager;
    }

    public Repository(Driver driver, String label, EntityMapper<T> entityMapper, RepositoryRegistry registry,
            RelationVisitor relationVisitor, TransactionManager txManager) {
        this.driver = driver;
        this.label = label;
        this.entityMapper = entityMapper;
        this.registry = registry;
        this.relationLoader = null;
        this.relationVisitor = relationVisitor;
        this.txManager = txManager;
    }

    public Repository(Driver driver, String label, EntityMapper<T> entityMapper, RepositoryRegistry registry,
            RelationLoader<T> relationLoader, RelationVisitor relationVisitor, TransactionManager txManager) {
        this.driver = driver;
        this.label = label;
        this.entityMapper = entityMapper;
        this.registry = registry;
        this.relationLoader = relationLoader;
        this.relationVisitor = relationVisitor;
        this.txManager = txManager;
    }

    protected abstract Class<T> getEntityType();

    public RelationLoader<T> getRelationLoader() {
        return relationLoader;
    }

    // ========================= Transaction Helpers (mit Übersetzung) =========================

    private <R> R inWriteTx(Function<Transaction, R> work) {
        try {
            if (txManager != null && txManager.isTransactionActive()) {
                return work.apply(txManager.getOrCreateTransaction());
            }
            try (Session session = driver.session();
                    Transaction tx = session.beginTransaction()) {
                R result = work.apply(tx);
                tx.commit();
                return result;
            }
        } catch (Exception e) {
            throw Neo4jExceptionTranslator.translate(e, "write-tx");
        }
    }

    private void inWriteTxVoid(Consumer<Transaction> work) {
        try {
            if (txManager != null && txManager.isTransactionActive()) {
                work.accept(txManager.getOrCreateTransaction());
                return;
            }
            try (Session session = driver.session();
                    Transaction tx = session.beginTransaction()) {
                work.accept(tx);
                tx.commit();
            }
        } catch (Exception e) {
            throw Neo4jExceptionTranslator.translate(e, "write-tx");
        }
    }

    private <R> R inReadTx(Function<Transaction, R> work) {
        try {
            if (txManager != null && txManager.isTransactionActive()) {
                return work.apply(txManager.getOrCreateTransaction());
            }
            try (Session session = driver.session();
                    Transaction tx = session.beginTransaction()) {
                return work.apply(tx);
            }
        } catch (Exception e) {
            throw Neo4jExceptionTranslator.translate(e, "read-tx");
        }
    }

    // ========================= Core Repository Methods =========================

    public T findById(Object id) {
        try {
            return inReadTx(tx -> {
                var result = tx.run(
                        "MATCH (n:" + label + " {id: $id}) RETURN n AS node",
                        Values.parameters("id", id.toString()));

                if (!result.hasNext()) {
                    throw new NotFoundRepositoryException(
                            getEntityType().getSimpleName() + " not found for id=" + id);
                }

                Record rec = result.next();
                T entity = entityMapper.map(rec, resolveAlias(rec));
                loadRelations(entity, 0);
                return entity;
            });
        } finally {
            resetVisitor();
        }
    }

    public Optional<T> findByIdOptional(Object id) {
        try {
            return inReadTx(tx -> {
                var result = tx.run(
                        "MATCH (n:" + label + " {id: $id}) RETURN n AS node",
                        Values.parameters("id", id.toString()));

                if (!result.hasNext()) {
                    return Optional.empty();
                }

                Record rec = result.next();
                T entity = entityMapper.map(rec, resolveAlias(rec));
                loadRelations(entity, 0);
                return Optional.of(entity);
            });
        } finally {
            resetVisitor();
        }
    }

    public List<T> findAll() {
        try {
            return inReadTx(tx -> {
                var result = tx.run("MATCH (n:" + label + ") RETURN n AS node");
                List<T> entities = result.list(rec -> {
                    String alias = resolveAlias(rec);
                    return entityMapper.map(rec, alias);
                });
                entities.forEach(e -> loadRelations(e, 0));
                return entities;
            });
        } finally {
            if (relationVisitor != null)
                relationVisitor.reset();
        }
    }

    public List<T> findAll(Pageable pageable, Sortable sortable) {
        try {
            return inReadTx(tx -> {
                String sortClause = (sortable != null) ? sortable.toCypher("n") : "";
                String cypher = String.format("MATCH (n:%s) RETURN n AS node %s SKIP $skip LIMIT $limit", label, sortClause);
                Map<String, Object> params = Map.of("skip", pageable.page() * pageable.size(), "limit", pageable.size());
                var result = tx.run(cypher, params);
                List<T> entities = result.list(rec -> {
                    String alias = resolveAlias(rec);
                    return entityMapper.map(rec, alias);
                });
                entities.forEach(e -> loadRelations(e, 0));
                return entities;
            });
        } finally {
            if (relationVisitor != null)
                relationVisitor.reset();
        }
    }

    public Paged<T> findAllPaged(Pageable pageable, Sortable sortable) {
        try {
            String sortClause = (sortable != null) ? sortable.toCypher("n") : "";

            // Single transaction for both content and count - avoids N+1 and ensures consistency
            return inReadTx(tx -> {
                // Count query
                String countCypher = String.format("MATCH (n:%s) RETURN count(n) AS count", label);
                long total = tx.run(countCypher).single().get("count").asLong();

                // Content query
                String contentCypher = String.format("MATCH (n:%s) RETURN n AS node %s SKIP $skip LIMIT $limit", label,
                        sortClause);
                Map<String, Object> params = Map.of("skip", pageable.page() * pageable.size(), "limit", pageable.size());
                var result = tx.run(contentCypher, params);
                List<T> entities = result.list(rec -> {
                    String alias = resolveAlias(rec);
                    return entityMapper.map(rec, alias);
                });
                entities.forEach(e -> loadRelations(e, 0));

                return new Paged<>(entities, total, pageable.page(), pageable.size());
            });
        } finally {
            if (relationVisitor != null)
                relationVisitor.reset();
        }
    }

    public long count() {
        try {
            return inReadTx(tx -> {
                var result = tx.run("MATCH (n:" + label + ") RETURN count(n) AS count");
                return result.hasNext() ? result.next().get("count").asLong() : 0L;
            });
        } finally {
            if (relationVisitor != null)
                relationVisitor.reset();
        }
    }

    public T create(T entity) {
        try {
            return inWriteTx(tx -> {
                EntityWithRelations data = entityMapper.toDb(entity);
                Object id = entityMapper.getNodeId(entity);

                var result = tx.run("CREATE (n:" + label + " $props) RETURN n AS node",
                        Values.parameters("props", data.getProperties()));
                Record rec = result.single();
                String alias = resolveAlias(rec);
                T saved = entityMapper.map(rec, alias);

                persistRelationships(tx, label, id, data.getRelationships(), new HashSet<>());
                return saved;
            });
        } finally {
            if (relationVisitor != null)
                relationVisitor.reset();
        }
    }

    /**
     * Batch create multiple entities in a single transaction using UNWIND for optimal performance.
     * Relationships are NOT persisted in batch mode - use createAll() if you need relationship persistence.
     *
     * @param entities the entities to create
     * @return list of created entities
     */
    public List<T> createAllBatch(List<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }

        try {
            return inWriteTx(tx -> {
                List<Map<String, Object>> propsList = new ArrayList<>();
                for (T entity : entities) {
                    EntityWithRelations data = entityMapper.toDb(entity);
                    propsList.add(data.getProperties());
                }

                String cypher = "UNWIND $batch AS props CREATE (n:" + label + ") SET n = props RETURN n AS node";
                var result = tx.run(cypher, Values.parameters("batch", propsList));

                return result.list(rec -> {
                    String alias = resolveAlias(rec);
                    return entityMapper.map(rec, alias);
                });
            });
        } finally {
            if (relationVisitor != null)
                relationVisitor.reset();
        }
    }

    /**
     * Create multiple entities with relationship persistence.
     * Uses a single transaction but creates entities individually to support relationships.
     *
     * @param entities the entities to create
     * @return list of created entities
     */
    public List<T> createAll(List<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }

        try {
            return inWriteTx(tx -> {
                List<T> results = new ArrayList<>();
                for (T entity : entities) {
                    EntityWithRelations data = entityMapper.toDb(entity);
                    Object id = entityMapper.getNodeId(entity);

                    var result = tx.run("CREATE (n:" + label + " $props) RETURN n AS node",
                            Values.parameters("props", data.getProperties()));
                    Record rec = result.single();
                    String alias = resolveAlias(rec);
                    T saved = entityMapper.map(rec, alias);

                    persistRelationships(tx, label, id, data.getRelationships(), new HashSet<>());
                    results.add(saved);
                }
                return results;
            });
        } finally {
            if (relationVisitor != null)
                relationVisitor.reset();
        }
    }

    /**
     * Batch delete multiple entities by their IDs in a single query.
     *
     * @param ids the IDs of entities to delete
     */
    public void deleteAllByIds(List<Object> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }

        try {
            List<String> idStrings = ids.stream()
                    .filter(id -> id != null)
                    .map(Object::toString)
                    .toList();

            inWriteTxVoid(tx -> tx.run(
                    "MATCH (n:" + label + ") WHERE n.id IN $ids DETACH DELETE n",
                    Values.parameters("ids", idStrings)).consume());
        } finally {
            if (relationVisitor != null)
                relationVisitor.reset();
        }
    }

    /**
     * Batch merge multiple entities using UNWIND for optimal performance.
     *
     * @param entities the entities to merge
     * @return list of merged entities
     */
    public List<T> mergeAllBatch(List<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }

        try {
            return inWriteTx(tx -> {
                String idProp = entityMapper.getNodeIdPropertyName();
                List<Map<String, Object>> propsList = new ArrayList<>();

                for (T entity : entities) {
                    EntityWithRelations data = entityMapper.toDb(entity);
                    propsList.add(data.getProperties());
                }

                String cypher = "UNWIND $batch AS props " +
                        "MERGE (n:" + label + " {" + idProp + ": props." + idProp + "}) " +
                        "SET n += props " +
                        "RETURN n AS node";

                var result = tx.run(cypher, Values.parameters("batch", propsList));

                return result.list(rec -> {
                    String alias = resolveAlias(rec);
                    return entityMapper.map(rec, alias);
                });
            });
        } finally {
            if (relationVisitor != null)
                relationVisitor.reset();
        }
    }

    private T createInternal(Transaction tx, EntityWithRelations entity) {
        Map<String, Object> props = entity.getProperties();
        String idProp = entityMapper.getNodeIdPropertyName();
        Object id = props.get(idProp);

        if (id == null) {
            throw new IllegalStateException("No @NodeId value present");
        }

        String cypher = "MERGE (n:" + label + " {" + idProp + ": $" + idProp + "}) " +
                "SET n += $props " +
                "RETURN n AS node";

        Map<String, Object> params = new HashMap<>();
        params.put(idProp, id);
        params.put("props", props);

        var result = tx.run(cypher, params);

        if (!result.hasNext()) {
            throw new IllegalStateException(
                    "MERGE did not return a node for "
                            + getEntityType().getSimpleName()
                            + " with id=" + id);
        }

        Record rec = result.next();
        T saved = entityMapper.map(rec, resolveAlias(rec));

        persistRelationships(tx, label, id, entity.getRelationships(), new HashSet<>());
        return saved;
    }

    public T update(T entity) {
        Object id = entityMapper.getNodeId(entity);
        if (id == null) {
            throw new IllegalArgumentException("Entity ID must not be null");
        }

        try {
            return inWriteTx(tx -> {
                EntityWithRelations data = entityMapper.toDb(entity);

                var result = tx.run(
                        "MATCH (n:" + label + " {id: $id}) SET n += $props RETURN n AS node",
                        Values.parameters("id", id.toString(), "props", data.getProperties()));

                if (!result.hasNext()) {
                    throw new NotFoundRepositoryException(
                            getEntityType().getSimpleName() + " not found for id=" + id);
                }

                Record rec = result.next();
                T updated = entityMapper.map(rec, resolveAlias(rec));
                persistRelationships(tx, label, id, data.getRelationships(), new HashSet<>());
                return updated;
            });
        } finally {
            resetVisitor();
        }
    }

    public T merge(T entity) {
        Object id = entityMapper.getNodeId(entity);
        if (id == null) {
            throw new IllegalArgumentException("Entity ID cannot be null");
        }

        try {
            return inWriteTx(tx -> {
                EntityWithRelations data = entityMapper.toDb(entity);

                var result = tx.run(
                        "MERGE (n:" + label + " {id: $id}) " +
                                "SET n += $props " +
                                "RETURN n AS node",
                        Values.parameters(
                                "id", id.toString(),
                                "props", data.getProperties()));

                if (!result.hasNext()) {
                    throw new IllegalStateException(
                            "MERGE did not return a node for "
                                    + getEntityType().getSimpleName()
                                    + " with id=" + id);
                }

                Record rec = result.next();
                T merged = entityMapper.map(rec, resolveAlias(rec));

                persistRelationships(tx, label, id, data.getRelationships(), new HashSet<>());
                return merged;
            });
        } finally {
            resetVisitor();
        }
    }

    public void delete(T entity) {
        deleteById(entityMapper.getNodeId(entity));
    }

    public void deleteById(Object id) {
        try {
            if (id == null)
                throw new IllegalArgumentException("ID cannot be null");
            inWriteTxVoid(
                    tx -> tx.run("MATCH (n:" + label + " {id: $id}) DETACH DELETE n", Values.parameters("id", id.toString())).consume());
        } finally {
            if (relationVisitor != null)
                relationVisitor.reset();
        }
    }

    public boolean existsById(Object id) {
        try {
            return inReadTx(tx -> {
                var result = tx.run("MATCH (n:" + label + " {id: $id}) RETURN count(n) > 0 AS exists",
                        Values.parameters("id", id.toString()));
                return result.single().get("exists").asBoolean();
            });
        } finally {
            if (relationVisitor != null)
                relationVisitor.reset();
        }
    }

    public boolean exists(T entity) {
        return existsById(entityMapper.getNodeId(entity));
    }

    // ========================= Query Methods =========================

    public List<T> query(String cypher) {
        return query(cypher, Map.of());
    }

    public List<T> query(String cypher, Pageable pageable, Sortable sortable) {
        return query(cypher, Map.of(), pageable, sortable);
    }

    public List<T> query(String cypher, Map<String, Object> parameters) {
        try {
            return inReadTx(tx -> {
                List<T> results = tx.run(cypher, Values.value(parameters))
                        .list(rec -> {
                            String alias = resolveAlias(rec);
                            return entityMapper.map(rec, alias);
                        });
                results.forEach(e -> loadRelations(e, 0));
                return results;
            });
        } finally {
            if (relationVisitor != null)
                relationVisitor.reset();
        }
    }

    public List<T> query(String cypher, Map<String, Object> parameters, Pageable pageable, Sortable sortable) {
        try {
            return inReadTx(tx -> {
                String sortClause = (sortable != null) ? sortable.toCypher("n") : "";
                String pagedCypher = String.format("%s %s SKIP $skip LIMIT $limit", cypher, sortClause);
                Map<String, Object> params = new HashMap<>(parameters);
                params.put("skip", pageable.page() * pageable.size());
                params.put("limit", pageable.size());
                var result = tx.run(pagedCypher, params);
                List<T> entities = result.list(rec -> {
                    String alias = resolveAlias(rec);
                    return entityMapper.map(rec, alias);
                });
                entities.forEach(e -> loadRelations(e, 0));
                return entities;
            });
        } finally {
            if (relationVisitor != null)
                relationVisitor.reset();
        }
    }

    public Paged<T> queryPaged(String baseCypher, Filter filter, Pageable pageable, Sortable sortable) {
        return queryPaged(baseCypher, filter, Map.of(), pageable, sortable);
    }

    public Paged<T> queryPaged(String baseCypher, Filter filter, Map<String, Object> parameters,
            Pageable pageable, Sortable sortable) {
        try {
            Filter.CypherFragment frag = (filter != null) ? filter.toCypher("n") : new Filter.CypherFragment("", Map.of());
            String sortClause = (sortable != null) ? sortable.toCypher("n") : "";

            Map<String, Object> allParams = new HashMap<>(parameters);
            allParams.putAll(frag.params());
            allParams.put("skip", pageable.page() * pageable.size());
            allParams.put("limit", pageable.size());

            // Single transaction for both count and content - avoids inconsistency and extra roundtrip
            return inReadTx(tx -> {
                // Count query first
                String countCypher = String.format("%s %s RETURN count(n) AS count", baseCypher, frag.clause());
                long total = tx.run(countCypher, allParams).single().get("count").asLong();

                // Content query
                String pagedCypher = String.format("%s %s RETURN n AS node %s SKIP $skip LIMIT $limit",
                        baseCypher, frag.clause(), sortClause);
                var result = tx.run(pagedCypher, allParams);
                List<T> entities = result.list(rec -> {
                    String alias = resolveAlias(rec);
                    return entityMapper.map(rec, alias);
                });
                entities.forEach(e -> loadRelations(e, 0));

                return new Paged<>(entities, total, pageable.page(), pageable.size());
            });
        } finally {
            if (relationVisitor != null)
                relationVisitor.reset();
        }
    }

    public Paged<T> queryPaged(String baseCypher, Map<String, Object> parameters, Pageable pageable, Sortable sortable) {
        try {
            String sortClause = (sortable != null) ? sortable.toCypher("n") : "";
            Map<String, Object> allParams = new HashMap<>(parameters);
            allParams.put("skip", pageable.page() * pageable.size());
            allParams.put("limit", pageable.size());

            // Single transaction for both count and content - avoids inconsistency and extra roundtrip
            return inReadTx(tx -> {
                // Count query first
                String countCypher = baseCypher + " RETURN count(n) AS count";
                long total = tx.run(countCypher, parameters).single().get("count").asLong();

                // Content query
                String pagedCypher = baseCypher + " RETURN n AS node " + sortClause + " SKIP $skip LIMIT $limit";
                var result = tx.run(pagedCypher, allParams);
                List<T> entities = result.list(rec -> {
                    String alias = resolveAlias(rec);
                    return entityMapper.map(rec, alias);
                });
                entities.forEach(e -> loadRelations(e, 0));

                return new Paged<>(entities, total, pageable.page(), pageable.size());
            });
        } finally {
            if (relationVisitor != null)
                relationVisitor.reset();
        }
    }

    public T querySingle(String cypher) {
        return querySingle(cypher, Map.of());
    }

    public T querySingle(String cypher, Map<String, Object> parameters) {
        try {
            return inReadTx(tx -> {
                var result = tx.run(cypher, Values.value(parameters));
                if (!result.hasNext())
                    return null;
                Record rec = result.next();
                String alias = resolveAlias(rec);
                T entity = entityMapper.map(rec, alias);
                loadRelations(entity, 0);
                return entity;
            });
        } finally {
            if (relationVisitor != null)
                relationVisitor.reset();
        }
    }

    public Optional<T> querySingleOptional(String cypher) {
        return querySingleOptional(cypher, Map.of());
    }

    public Optional<T> querySingleOptional(String cypher, Map<String, Object> parameters) {
        try {
            return inReadTx(tx -> {
                var result = tx.run(cypher, Values.value(parameters));
                if (!result.hasNext())
                    return Optional.empty();
                Record rec = result.next();
                String alias = resolveAlias(rec);
                T entity = entityMapper.map(rec, alias);
                loadRelations(entity, 0);
                return Optional.of(entity);
            });
        } finally {
            if (relationVisitor != null)
                relationVisitor.reset();
        }
    }

    public void execute(String cypher, Map<String, Object> parameters) {
        inWriteTxVoid(tx -> tx.run(cypher, Values.value(parameters)).consume());
    }

    public T executeReturning(String cypher, Map<String, Object> parameters) {
        try {
            return inWriteTx(tx -> {
                var result = tx.run(cypher, Values.value(parameters));
                if (!result.hasNext())
                    return null;
                Record rec = result.next();
                String alias = resolveAlias(rec);
                T entity = entityMapper.map(rec, alias);
                loadRelations(entity, 0);
                return entity;
            });
        } finally {
            if (relationVisitor != null)
                relationVisitor.reset();
        }
    }

    public List<T> executeQuery(String cypher, Map<String, Object> parameters) {
        try {
            return inWriteTx(tx -> {
                var result = tx.run(cypher, Values.value(parameters));
                List<T> entities = result.list(rec -> {
                    String alias = resolveAlias(rec);
                    return entityMapper.map(rec, alias);
                });
                entities.forEach(e -> loadRelations(e, 0));
                return entities;
            });
        } finally {
            if (relationVisitor != null)
                relationVisitor.reset();
        }
    }

    public <R> R queryScalar(String cypher, Function<Record, R> mapper) {
        return queryScalar(cypher, Map.of(), mapper);
    }

    public <R> R queryScalar(String cypher, Map<String, Object> parameters, Function<Record, R> mapper) {
        return inReadTx(tx -> {
            var result = tx.run(cypher, Values.value(parameters));
            if (!result.hasNext()) {
                return null;
            }
            return mapper.apply(result.next());
        });
    }

    // ========================= Relation Loading mit CDI Visitor =========================

    protected void loadRelations(T entity, int depth) {
        if (entity == null || relationLoader == null || relationVisitor == null)
            return;
        if (!relationVisitor.shouldVisit(entity, depth))
            return;
        relationLoader.loadRelations(entity, depth);
    }

    @SuppressWarnings("unchecked")
    protected void loadRelationsForAnyEntity(Object entity, int currentDepth) {
        if (entity == null)
            return;
        if (!relationVisitor.shouldVisit(entity, currentDepth))
            return;

        Class<?> entityClass = entity.getClass();
        Repository<Object> repository = (Repository<Object>) registry.getRepository(entityClass);

        if (repository != null && repository.getRelationLoader() != null) {
            repository.getRelationLoader().loadRelations(entity, currentDepth);
        }
    }

    protected void resetVisitor() {
        if (relationVisitor != null) {
            relationVisitor.reset();
        }
    }

    // ========================= Relationship Persistence =========================

    private void persistRelationships(
            Transaction tx,
            String sourceLabel,
            Object fromId,
            List<RelationshipData> relationships,
            Set<String> visited) {

        if (fromId == null || relationships == null || relationships.isEmpty()) {
            return;
        }

        if (relationVisitor == null) {
            throw new IllegalStateException("RelationVisitor is required but not available");
        }

        if (!relationVisitor.markPersisted(sourceLabel, fromId)) {
            return;
        }

        for (RelationshipData rel : relationships) {

            if (rel.getMode() == RelationshipMode.FETCH_ONLY) {
                continue;
            }

            Object toId = rel.getTargetId();
            EntityWithRelations target = rel.getTarget();
            Repository<Object> targetRepo = null;

            if (target != null) {
                targetRepo = (Repository<Object>) registry.getRepository(target.getEntityType());

                String idPropertyName = targetRepo.entityMapper.getNodeIdPropertyName();
                Object targetEntityId = target.getProperties().get(idPropertyName);

                if (targetEntityId != null) {
                    if (!relationVisitor.wasPersisted(targetRepo.label, targetEntityId)) {
                        Object merged = targetRepo.createInternal(tx, target); // ← TX übergeben!
                        toId = targetRepo.entityMapper.getNodeId(merged);
                        rel.setTargetId(toId);
                    } else {
                        toId = targetEntityId;
                    }
                } else {
                    Object merged = targetRepo.createInternal(tx, target); // ← TX übergeben!
                    toId = targetRepo.entityMapper.getNodeId(merged);
                    rel.setTargetId(toId);
                }
            }

            if (toId == null) {
                continue;
            }

            if (targetRepo == null) {
                targetRepo = (Repository<Object>) registry.getRepository(
                        target != null ? target.getEntityType() : null);
                if (targetRepo == null) {
                    continue;
                }
            }

            String targetLabel = targetRepo.label;

            Map<String, Object> params = Map.of(
                    "from", fromId.toString(),
                    "to", toId.toString());

            switch (rel.getDirection()) {
                case OUTGOING -> tx.run(
                        "MATCH (a:" + sourceLabel + " {id: $from}), " +
                                "      (b:" + targetLabel + " {id: $to}) " +
                                "MERGE (a)-[:" + rel.getType() + "]->(b)",
                        params);

                case INCOMING -> tx.run(
                        "MATCH (a:" + sourceLabel + " {id: $from}), " +
                                "      (b:" + targetLabel + " {id: $to}) " +
                                "MERGE (a)<-[:" + rel.getType() + "]-(b)",
                        params);

                case UNDIRECTED -> tx.run(
                        "MATCH (a:" + sourceLabel + " {id: $from}), " +
                                "      (b:" + targetLabel + " {id: $to}) " +
                                "MERGE (a)-[:" + rel.getType() + "]-(b)",
                        params);

                case BOTH -> {
                    tx.run(
                            "MATCH (a:" + sourceLabel + " {id: $from}), " +
                                    "      (b:" + targetLabel + " {id: $to}) " +
                                    "MERGE (a)-[:" + rel.getType() + "]->(b)",
                            params);
                    tx.run(
                            "MATCH (a:" + sourceLabel + " {id: $from}), " +
                                    "      (b:" + targetLabel + " {id: $to}) " +
                                    "MERGE (a)<-[:" + rel.getType() + "]-(b)",
                            params);
                }
            }
        }
    }

    public EntityMapper<T> getEntityMapper() {
        return entityMapper;
    }

    // ========================= Monitoring =========================

    public RelationVisitor.VisitorStats getVisitorStats() {
        return relationVisitor != null
                ? relationVisitor.getStats()
                : null;
    }

    public List<RelationVisitor.TraversalStep> getTraversalPath() {
        return relationVisitor != null ? relationVisitor.getTraversalPath() : null;
    }

    // ========================= Alias Resolution Helper =========================

    protected String resolveAlias(Record rec) {
        return rec.keys().isEmpty() ? "node" : rec.keys().getFirst();
    }
}
