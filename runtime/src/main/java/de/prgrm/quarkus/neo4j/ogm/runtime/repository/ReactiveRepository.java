package de.prgrm.quarkus.neo4j.ogm.runtime.repository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Values;
import org.neo4j.driver.reactive.ReactiveResult;
import org.neo4j.driver.reactive.ReactiveSession;

import de.prgrm.quarkus.neo4j.ogm.runtime.exception.RepositoryException;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.*;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.EntityMapper;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.EntityWithRelations;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.ReactiveRelationLoader;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.RelationshipData;
import de.prgrm.quarkus.neo4j.ogm.runtime.repository.util.Pageable;
import de.prgrm.quarkus.neo4j.ogm.runtime.repository.util.Paged;
import de.prgrm.quarkus.neo4j.ogm.runtime.repository.util.Sortable;
import de.prgrm.quarkus.neo4j.ogm.runtime.tx.ReactiveTransactionManager;
import de.prgrm.quarkus.neo4j.ogm.runtime.tx.ReactiveTransactionManager.ReactiveTxContext;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

/**
 * Reactive repository base class for Neo4j OGM.
 */
public abstract class ReactiveRepository<T> {

    protected final Driver driver;
    protected final String label;
    protected final EntityMapper<T> entityMapper;
    protected final ReactiveRepositoryRegistry reactiveRegistry;
    protected final ReactiveRelationLoader<T> relationLoader;
    protected final ReactiveRelationVisitor relationVisitor;
    protected final ReactiveTransactionManager txManager;

    /** Shared visitor context across traversal */
    protected ReactiveRelationVisitor.VisitorContext visitorContext;

    public ReactiveRepository() {
        this.driver = null;
        this.label = null;
        this.entityMapper = null;
        this.reactiveRegistry = null;
        this.relationLoader = null;
        this.relationVisitor = null;
        this.visitorContext = null;
        this.txManager = null;
    }

    public ReactiveRepository(Driver driver, String label, EntityMapper<T> entityMapper,
            ReactiveRepositoryRegistry reactiveRegistry, ReactiveTransactionManager txManager) {
        this(driver, label, entityMapper, reactiveRegistry, null, null, txManager);
    }

    public ReactiveRepository(Driver driver, String label, EntityMapper<T> entityMapper,
            ReactiveRepositoryRegistry reactiveRegistry,
            ReactiveRelationVisitor relationVisitor, ReactiveTransactionManager txManager) {
        this(driver, label, entityMapper, reactiveRegistry, null, relationVisitor, txManager);
    }

    public ReactiveRepository(Driver driver, String label, EntityMapper<T> entityMapper,
            ReactiveRepositoryRegistry reactiveRegistry,
            ReactiveRelationLoader<T> relationLoader,
            ReactiveRelationVisitor relationVisitor, ReactiveTransactionManager txManager) {
        this.driver = driver;
        this.label = label;
        this.entityMapper = entityMapper;
        this.reactiveRegistry = reactiveRegistry;
        this.relationLoader = relationLoader;
        this.relationVisitor = relationVisitor;
        this.visitorContext = null;
        this.txManager = txManager;
    }

    protected abstract Class<T> getEntityType();

    // ----------------------------------------------------------
    // Visitor context handling
    // ----------------------------------------------------------

    /**
     * Create a new visitor context if none exists yet.
     * Does NOT overwrite an existing one to allow propagation.
     */
    private void resetVisitor() {
        if (relationVisitor != null && this.visitorContext == null) {
            this.visitorContext = relationVisitor.newContext();
        }
    }

    /** Allow external components (RelationLoader) to pass a shared context. */
    public void setVisitorContext(ReactiveRelationVisitor.VisitorContext ctx) {
        this.visitorContext = ctx;
    }

    public ReactiveRelationVisitor.VisitorStats getVisitorStats() {
        return visitorContext != null ? visitorContext.getStats() : null;
    }

    public List<ReactiveRelationVisitor.TraversalStep> getTraversalPath() {
        return visitorContext != null ? visitorContext.traversalPath : List.of();
    }

    // ----------------------------------------------------------
    // Public Repository API (non-transactional: open/close per call)
    // ----------------------------------------------------------

    public Uni<T> findById(Object id) {
        resetVisitor();
        return runReadQuerySingle(null, "MATCH (n:" + label + " {id: $id}) RETURN n", Map.of("id", id))
                .flatMap(this::loadRelations);
    }

    public Multi<T> findAll() {
        resetVisitor();
        return runReadQuery(null, "MATCH (n:" + label + ") RETURN n", Map.of())
                .onItem().transformToUniAndMerge(this::loadRelations);
    }

    public Multi<T> findAll(Pageable pageable, Sortable sortable) {
        resetVisitor();
        String sortClause = (sortable != null) ? sortable.toCypher("n") : "";
        String cypher = String.format("MATCH (n:%s) RETURN n %s SKIP $skip LIMIT $limit", label, sortClause);
        Map<String, Object> params = Map.of(
                "skip", pageable.page() * pageable.size(),
                "limit", pageable.size());
        return runReadQuery(null, cypher, params)
                .onItem().transformToUniAndMerge(this::loadRelations);
    }

    public Uni<Paged<T>> findAllPaged(Pageable pageable, Sortable sortable) {
        resetVisitor();
        String sortClause = (sortable != null) ? sortable.toCypher("n") : "";
        String cypher = String.format("MATCH (n:%s) RETURN n %s SKIP $skip LIMIT $limit", label, sortClause);
        Map<String, Object> params = Map.of(
                "skip", pageable.page() * pageable.size(),
                "limit", pageable.size());

        Uni<Long> countUni = count();
        Uni<List<T>> contentUni = runReadQuery(null, cypher, params)
                .onItem().transformToUniAndMerge(this::loadRelations)
                .collect().asList();

        return Uni.combine().all().unis(contentUni, countUni)
                .asTuple()
                .map(tuple -> new Paged<>(
                        tuple.getItem1(),
                        tuple.getItem2(),
                        pageable.page(),
                        pageable.size()));
    }

    public Uni<Void> execute(String cypher, Map<String, Object> parameters) {
        return runWriteQueryVoid(null, cypher, parameters);
    }

    public Uni<T> executeReturning(String cypher, Map<String, Object> parameters) {
        resetVisitor();
        return runWriteQuerySingle(null, cypher, parameters)
                .flatMap(this::loadRelations);
    }

    public Multi<T> executeQuery(String cypher, Map<String, Object> parameters) {
        resetVisitor();
        return runQueryInternal(null, cypher, parameters, false)
                .map(r -> entityMapper.map(r, resolveAlias(r)))
                .onItem().transformToUniAndMerge(this::loadRelations);
    }

    public Uni<Long> count() {
        return runScalarReadQuery(null, "MATCH (n:" + label + ") RETURN count(n) AS count", Map.of(),
                r -> r.get("count").asLong());
    }

    public Uni<T> create(T entity) {
        resetVisitor();
        EntityWithRelations data = entityMapper.toDb(entity);
        Object id = entityMapper.getNodeId(entity);

        return runWriteQuerySingle(null, "CREATE (n:" + label + " $props) RETURN n",
                Map.of("props", data.getProperties()))
                .flatMap(saved -> persistRelationships(null, label, id, data.getRelationships()).replaceWith(saved));
    }

    private Uni<T> createInternal(EntityWithRelations entity) {
        return createInternal(null, entity);
    }

    public Uni<T> update(T entity) {
        resetVisitor();
        Object id = entityMapper.getNodeId(entity);
        EntityWithRelations data = entityMapper.toDb(entity);

        return runWriteQuerySingle(null, "MATCH (n:" + label + " {id: $id}) SET n += $props RETURN n",
                Map.of("id", id, "props", data.getProperties()))
                .flatMap(updated -> persistRelationships(null, label, id, data.getRelationships()).replaceWith(updated));
    }

    public Uni<T> merge(T entity) {
        resetVisitor();
        Object id = entityMapper.getNodeId(entity);
        EntityWithRelations data = entityMapper.toDb(entity);

        return runWriteQuerySingle(null, "MERGE (n:" + label + " {id: $id}) SET n += $props RETURN n",
                Map.of("id", id, "props", data.getProperties()))
                .flatMap(merged -> persistRelationships(null, label, id, data.getRelationships()).replaceWith(merged));
    }

    public Uni<Void> delete(T entity) {
        return deleteById(entityMapper.getNodeId(entity));
    }

    public Uni<Void> deleteById(Object id) {
        resetVisitor();
        return runWriteQueryVoid(null, "MATCH (n:" + label + " {id: $id}) DELETE n", Map.of("id", id));
    }

    public Uni<Boolean> existsById(Object id) {
        return runScalarReadQuery(null, "MATCH (n:" + label + " {id: $id}) RETURN count(n) > 0 AS exists",
                Map.of("id", id), r -> r.get("exists").asBoolean());
    }

    public Uni<Boolean> exists(T entity) {
        return existsById(entityMapper.getNodeId(entity));
    }

    public Multi<T> query(String cypher) {
        resetVisitor();
        return runReadQuery(null, cypher, Map.of())
                .onItem().transformToUniAndMerge(this::loadRelations);
    }

    public Multi<T> query(String cypher, Map<String, Object> params) {
        resetVisitor();
        return runReadQuery(null, cypher, params)
                .onItem().transformToUniAndMerge(this::loadRelations);
    }

    public Uni<T> querySingle(String cypher) {
        return querySingle(cypher, Map.of());
    }

    public Uni<T> querySingle(String cypher, Map<String, Object> params) {
        resetVisitor();
        return runReadQuerySingle(null, cypher, params)
                .flatMap(this::loadRelations);
    }

    public <R> Uni<R> queryScalar(String cypher, Function<Record, R> mapper) {
        return queryScalar(null, cypher, Map.of(), mapper);
    }

    public <R> Uni<R> queryScalar(String cypher,
            Map<String, Object> parameters,
            Function<Record, R> mapper) {
        return runScalarReadQuery(null, cypher, parameters, mapper);
    }

    // ----------------------------------------------------------
    // Public Repository API (transactional overloads using ReactiveTxContext)
    // ----------------------------------------------------------

    public Uni<T> findById(ReactiveTxContext ctx, Object id) {
        resetVisitor();
        return runReadQuerySingle(ctx, "MATCH (n:" + label + " {id: $id}) RETURN n", Map.of("id", id))
                .flatMap(e -> loadRelations(ctx, e));
    }

    public Multi<T> findAll(ReactiveTxContext ctx) {
        resetVisitor();
        return runReadQuery(ctx, "MATCH (n:" + label + ") RETURN n", Map.of())
                .onItem().transformToUniAndMerge(e -> loadRelations(ctx, e));
    }

    public Uni<T> create(ReactiveTxContext ctx, T entity) {
        resetVisitor();
        EntityWithRelations data = entityMapper.toDb(entity);
        Object id = entityMapper.getNodeId(entity);

        return runWriteQuerySingle(ctx, "CREATE (n:" + label + " $props) RETURN n",
                Map.of("props", data.getProperties()))
                .flatMap(saved -> persistRelationships(ctx, label, id, data.getRelationships()).replaceWith(saved));
    }

    public Uni<T> update(ReactiveTxContext ctx, T entity) {
        resetVisitor();
        Object id = entityMapper.getNodeId(entity);
        EntityWithRelations data = entityMapper.toDb(entity);

        return runWriteQuerySingle(ctx, "MATCH (n:" + label + " {id: $id}) SET n += $props RETURN n",
                Map.of("id", id, "props", data.getProperties()))
                .flatMap(updated -> persistRelationships(ctx, label, id, data.getRelationships()).replaceWith(updated));
    }

    public Uni<T> merge(ReactiveTxContext ctx, T entity) {
        resetVisitor();
        Object id = entityMapper.getNodeId(entity);
        EntityWithRelations data = entityMapper.toDb(entity);

        return runWriteQuerySingle(ctx, "MERGE (n:" + label + " {id: $id}) SET n += $props RETURN n",
                Map.of("id", id, "props", data.getProperties()))
                .flatMap(merged -> persistRelationships(ctx, label, id, data.getRelationships()).replaceWith(merged));
    }

    public Uni<Void> delete(ReactiveTxContext ctx, T entity) {
        return deleteById(ctx, entityMapper.getNodeId(entity));
    }

    public Uni<Void> deleteById(ReactiveTxContext ctx, Object id) {
        resetVisitor();
        return runWriteQueryVoid(ctx, "MATCH (n:" + label + " {id: $id}) DELETE n", Map.of("id", id));
    }

    public Uni<Boolean> existsById(ReactiveTxContext ctx, Object id) {
        return runScalarReadQuery(ctx, "MATCH (n:" + label + " {id: $id}) RETURN count(n) > 0 AS exists",
                Map.of("id", id), r -> r.get("exists").asBoolean());
    }

    public Multi<T> query(ReactiveTxContext ctx, String cypher, Map<String, Object> params) {
        resetVisitor();
        return runReadQuery(ctx, cypher, params)
                .onItem().transformToUniAndMerge(e -> loadRelations(ctx, e));
    }

    public Uni<T> querySingle(ReactiveTxContext ctx, String cypher, Map<String, Object> params) {
        resetVisitor();
        return runReadQuerySingle(ctx, cypher, params)
                .flatMap(e -> loadRelations(ctx, e));
    }

    public Uni<T> executeReturning(ReactiveTxContext ctx, String cypher, Map<String, Object> params) {
        resetVisitor();
        return runWriteQuerySingle(ctx, cypher, params)
                .flatMap(e -> loadRelations(ctx, e));
    }

    public Uni<Void> execute(ReactiveTxContext ctx, String cypher, Map<String, Object> params) {
        return runWriteQueryVoid(ctx, cypher, params);
    }

    public <R> Uni<R> queryScalar(ReactiveTxContext ctx, String cypher, Map<String, Object> parameters,
            Function<Record, R> mapper) {
        return runScalarReadQuery(ctx, cypher, parameters, mapper);
    }

    // ----------------------------------------------------------
    // Relations
    // ----------------------------------------------------------

    protected Uni<T> loadRelations(T entity, int currentDepth) {
        if (entity == null || relationLoader == null || relationVisitor == null) {
            return Uni.createFrom().item(entity);
        }

        return relationVisitor.shouldVisit(entity, currentDepth, visitorContext)
                .flatMap(shouldVisit -> {
                    if (!shouldVisit) {
                        return Uni.createFrom().item(entity);
                    }
                    return relationLoader.loadRelations(entity, currentDepth, visitorContext);
                });
    }

    protected Uni<T> loadRelations(T entity) {
        return loadRelations(entity, 0);
    }

    // Overload for transactional flow: no change in traversal, just returning same Uni
    protected Uni<T> loadRelations(ReactiveTxContext ctx, T entity) {
        return loadRelations(entity);
    }

    @SuppressWarnings("unchecked")
    private Uni<Object> loadRelationsForAnyEntity(Object entity, int currentDepth) {
        if (entity == null) {
            return Uni.createFrom().nullItem();
        }
        if (relationVisitor == null) {
            return Uni.createFrom().item(entity);
        }

        return relationVisitor.shouldVisit(entity, currentDepth, visitorContext)
                .flatMap(shouldVisit -> {
                    if (!shouldVisit) {
                        return Uni.createFrom().item(entity);
                    }
                    return relationVisitor.markVisited(entity, visitorContext)
                            .flatMap(ignore -> {
                                Class<?> entityClass = entity.getClass();
                                ReactiveRepository<Object> repo = (ReactiveRepository<Object>) reactiveRegistry
                                        .getReactiveRepository(entityClass);
                                if (repo != null && repo.getRelationLoader() != null) {
                                    repo.setVisitorContext(visitorContext); // share context
                                    ReactiveRelationLoader<Object> loader = (ReactiveRelationLoader<Object>) repo
                                            .getRelationLoader();
                                    return loader.loadRelations(entity, currentDepth, visitorContext);
                                }
                                return Uni.createFrom().item(entity);
                            });
                });
    }

    // ----------------------------------------------------------
    // Internals (query execution) - dual path: with ctx (tx.run) OR standalone (session resource)
    // ----------------------------------------------------------

    private static Function<ReactiveSession, Uni<Void>> closeSession() {
        return session -> Uni.createFrom().publisher(session.close())
                .replaceWithVoid()
                .onFailure()
                .invoke(throwable -> System.err.println("Failed to close Neo4j session: " + throwable.getMessage()));
    }

    // -------- Read (list) --------
    private Multi<T> runReadQuery(ReactiveTxContext ctx, String cypher, Map<String, Object> params) {
        return runQueryInternal(ctx, cypher, params, true)
                .map(r -> entityMapper.map(r, resolveAlias(r)));
    }

    // -------- Read (single) --------
    private Uni<T> runReadQuerySingle(ReactiveTxContext ctx, String cypher, Map<String, Object> params) {
        return runReadQuery(ctx, cypher, params).toUni();
    }

    // -------- Write (single returning) --------
    private Uni<T> runWriteQuerySingle(ReactiveTxContext ctx, String cypher, Map<String, Object> params) {
        return runQueryInternal(ctx, cypher, params, false)
                .collect().asList()
                .map(records -> records.isEmpty()
                        ? null
                        : entityMapper.map(records.get(0), resolveAlias(records.get(0))));
    }

    // -------- Write (void) --------
    private Uni<Void> runWriteQueryVoid(ReactiveTxContext ctx, String cypher, Map<String, Object> params) {
        if (ctx != null) {
            var result = ctx.getTx().run(cypher, Values.value(params));
            return Multi.createFrom().publisher(result)
                    .flatMap(ReactiveResult::consume)
                    .toUni()
                    .replaceWithVoid()
                    .onFailure().transform(t -> new RepositoryException("Failed to execute write query", t));
        }
        // fallback: open/close per call
        return Multi.createFrom().resource(
                () -> driver.session(ReactiveSession.class),
                session -> session.executeWrite(tx -> {
                    var result = tx.run(cypher, Values.value(params));
                    return Multi.createFrom().publisher(result)
                            .flatMap(ReactiveResult::consume)
                            .flatMap(ignore -> Multi.createFrom().item((Void) null));
                }))
                .withFinalizer(closeSession())
                .toUni()
                .onFailure().transform(t -> new RepositoryException("Failed to execute write query", t));
    }

    // -------- Scalar --------
    private <R> Uni<R> runScalarReadQuery(ReactiveTxContext ctx, String cypher, Map<String, Object> params,
            Function<Record, R> mapper) {
        if (ctx != null) {
            var result = ctx.getTx().run(cypher, Values.value(params));
            return Multi.createFrom().publisher(result)
                    .flatMap(ReactiveResult::records)
                    .map(mapper)
                    .toUni()
                    .onFailure().transform(t -> new RepositoryException("Failed to execute scalar query", t));
        }
        // fallback: open/close per call
        return Multi.createFrom().resource(
                () -> driver.session(ReactiveSession.class),
                session -> session.executeRead(tx -> {
                    var result = tx.run(cypher, Values.value(params));
                    return Multi.createFrom().publisher(result)
                            .flatMap(ReactiveResult::records)
                            .map(mapper);
                }))
                .withFinalizer(closeSession())
                .toUni()
                .onFailure().transform(t -> new RepositoryException("Failed to execute scalar query", t));
    }

    // -------- Internal main runner --------
    private Multi<Record> runQueryInternal(ReactiveTxContext ctx, String cypher, Map<String, Object> params, boolean readOnly) {
        if (ctx != null) {
            var result = ctx.getTx().run(cypher, Values.value(params));
            return Multi.createFrom().publisher(result)
                    .flatMap(ReactiveResult::records)
                    .onFailure().transform(t -> new RepositoryException("Failed to execute query", t));
        }
        // fallback: open/close per call
        return Multi.createFrom().resource(
                () -> driver.session(ReactiveSession.class),
                session -> {
                    if (readOnly) {
                        return session.executeRead(tx -> Multi.createFrom().publisher(tx.run(cypher, Values.value(params)))
                                .flatMap(ReactiveResult::records));
                    } else {
                        return session.executeWrite(tx -> Multi.createFrom().publisher(tx.run(cypher, Values.value(params)))
                                .flatMap(ReactiveResult::records));
                    }
                })
                .withFinalizer(closeSession())
                .onFailure().transform(t -> new RepositoryException("Failed to execute query", t));
    }

    // ----------------------------------------------------------
    // Relationship persistence – uses SAME tx when ctx provided
    // ----------------------------------------------------------

    private Uni<Void> persistRelationships(ReactiveTxContext ctx, String sourceLabel, Object fromId,
            List<RelationshipData> relationships) {
        if (fromId == null || relationships == null || relationships.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        if (relationVisitor == null) {
            return Uni.createFrom().failure(
                    new IllegalStateException("RelationVisitor is required but not available"));
        }

        return relationVisitor.markPersisted(sourceLabel, fromId, visitorContext)
                .flatMap(wasMarked -> {
                    if (!wasMarked) {
                        return Uni.createFrom().voidItem();
                    }

                    return Multi.createFrom().iterable(relationships)
                            .onItem().transformToUniAndMerge(rel -> {
                                if (rel.getMode() == de.prgrm.quarkus.neo4j.ogm.runtime.enums.RelationshipMode.FETCH_ONLY) {
                                    return Uni.createFrom().voidItem();
                                }

                                EntityWithRelations target = rel.getTarget();
                                if (target == null) {
                                    return Uni.createFrom().voidItem();
                                }

                                @SuppressWarnings("unchecked")
                                ReactiveRepository<Object> targetRepo = (ReactiveRepository<Object>) reactiveRegistry
                                        .getReactiveRepository(target.getEntityType());

                                if (targetRepo == null) {
                                    return Uni.createFrom().voidItem();
                                }

                                targetRepo.setVisitorContext(visitorContext); // share visitor context

                                String idPropertyName = targetRepo.getEntityMapper().getNodeIdPropertyName();
                                Object targetEntityId = target.getProperties().get(idPropertyName);

                                if (targetEntityId != null) {
                                    return relationVisitor.wasPersisted(targetRepo.label, targetEntityId, visitorContext)
                                            .flatMap(wasPersisted -> {
                                                if (!wasPersisted) {
                                                    return targetRepo.createInternal(ctx, target)
                                                            .invoke(saved -> {
                                                                Object id = targetRepo.getEntityMapper().getNodeId(saved);
                                                                rel.setTargetId(id);
                                                            })
                                                            .replaceWithVoid();
                                                } else {
                                                    // Entity bereits persistiert -> verwende nur die ID
                                                    rel.setTargetId(targetEntityId);
                                                    return Uni.createFrom().voidItem();
                                                }
                                            });
                                } else {
                                    return targetRepo.createInternal(ctx, target)
                                            .invoke(saved -> {
                                                Object id = targetRepo.getEntityMapper().getNodeId(saved);
                                                rel.setTargetId(id);
                                            })
                                            .replaceWithVoid();
                                }
                            })
                            .collect().asList()
                            .flatMap(ignore -> Multi.createFrom().iterable(relationships)
                                    .onItem().transformToUniAndMerge(rel -> {
                                        Object toId = rel.getTargetId();
                                        if (toId == null) {
                                            return Uni.createFrom().voidItem();
                                        }

                                        EntityWithRelations target = rel.getTarget();
                                        if (target == null) {
                                            return Uni.createFrom().voidItem();
                                        }

                                        @SuppressWarnings("unchecked")
                                        ReactiveRepository<Object> targetRepo = (ReactiveRepository<Object>) reactiveRegistry
                                                .getReactiveRepository(target.getEntityType());

                                        if (targetRepo == null) {
                                            return Uni.createFrom().voidItem();
                                        }

                                        String targetLabel = targetRepo.label;

                                        Map<String, Object> params = Map.of(
                                                "from", fromId.toString(),
                                                "to", toId.toString());

                                        String query = switch (rel.getDirection()) {
                                            case OUTGOING ->
                                                "MATCH (a:" + sourceLabel + " {id: $from}), " +
                                                        "      (b:" + targetLabel + " {id: $to}) " +
                                                        "MERGE (a)-[:" + rel.getType() + "]->(b)";
                                            case INCOMING ->
                                                "MATCH (a:" + sourceLabel + " {id: $from}), " +
                                                        "      (b:" + targetLabel + " {id: $to}) " +
                                                        "MERGE (a)<-[:" + rel.getType() + "]-(b)";
                                            case UNDIRECTED ->
                                                "MATCH (a:" + sourceLabel + " {id: $from}), " +
                                                        "      (b:" + targetLabel + " {id: $to}) " +
                                                        "MERGE (a)-[:" + rel.getType() + "]-(b)";
                                            case BOTH ->
                                                throw new UnsupportedOperationException(
                                                        "BOTH direction not yet implemented for reactive");
                                            default ->
                                                throw new UnsupportedOperationException(
                                                        "Unsupported direction: " + rel.getDirection());
                                        };

                                        return runWriteQueryVoid(ctx, query, params);
                                    })
                                    .collect().asList()
                                    .replaceWithVoid());
                });
    }

    private Uni<Void> persistRelationships(String sourceLabel, Object fromId, List<RelationshipData> relationships) {
        return persistRelationships(null, sourceLabel, fromId, relationships);
    }

    private Uni<T> createInternal(ReactiveTxContext ctx, EntityWithRelations entity) {
        resetVisitor();

        String idProp = entityMapper.getNodeIdPropertyName();
        String cypher = "MERGE (n:" + label + " {" + idProp + ": $props." + idProp + "}) " +
                "SET n += $props RETURN n";

        return runWriteQuerySingle(ctx, cypher, Map.of("props", entity.getProperties()))
                .flatMap(saved -> persistRelationships(ctx,
                        label,
                        entityMapper.getNodeId(saved),
                        entity.getRelationships())
                        .replaceWith(saved));
    }

    // ----------------------------------------------------------
    // Accessors
    // ----------------------------------------------------------

    public EntityMapper<T> getEntityMapper() {
        return entityMapper;
    }

    public ReactiveRelationLoader<T> getRelationLoader() {
        return relationLoader;
    }

    protected String resolveAlias(Record rec) {
        if (rec == null || rec.keys().isEmpty()) {
            return "node";
        }
        return rec.keys().getFirst();
    }
}
