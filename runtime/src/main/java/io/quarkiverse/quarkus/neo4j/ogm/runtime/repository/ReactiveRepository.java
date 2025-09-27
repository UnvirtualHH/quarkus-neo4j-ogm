package io.quarkiverse.quarkus.neo4j.ogm.runtime.repository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Values;
import org.neo4j.driver.reactive.ReactiveResult;
import org.neo4j.driver.reactive.ReactiveSession;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.exception.RepositoryException;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.*;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.repository.util.Pageable;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.repository.util.Paged;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.repository.util.Sortable;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

/**
 * Reactive repository base class for Neo4j OGM.
 * Uses ReactiveRelationVisitor (with explicit VisitorContext) for safe traversal.
 */
public abstract class ReactiveRepository<T> {

    protected final Driver driver;
    protected final String label;
    protected final EntityMapper<T> entityMapper;
    protected final ReactiveRepositoryRegistry reactiveRegistry;
    protected final ReactiveRelationLoader<T> relationLoader;
    protected final ReactiveRelationVisitor relationVisitor;

    /**
     * Context is created fresh per repository operation.
     */
    protected ReactiveRelationVisitor.VisitorContext visitorContext;

    public ReactiveRepository() {
        this.driver = null;
        this.label = null;
        this.entityMapper = null;
        this.reactiveRegistry = null;
        this.relationLoader = null;
        this.relationVisitor = null;
        this.visitorContext = null;
    }

    public ReactiveRepository(Driver driver, String label, EntityMapper<T> entityMapper,
            ReactiveRepositoryRegistry reactiveRegistry) {
        this.driver = driver;
        this.label = label;
        this.entityMapper = entityMapper;
        this.reactiveRegistry = reactiveRegistry;
        this.relationLoader = null;
        this.relationVisitor = null;
        this.visitorContext = null;
    }

    public ReactiveRepository(Driver driver, String label, EntityMapper<T> entityMapper,
            ReactiveRepositoryRegistry reactiveRegistry, ReactiveRelationVisitor relationVisitor) {
        this.driver = driver;
        this.label = label;
        this.entityMapper = entityMapper;
        this.reactiveRegistry = reactiveRegistry;
        this.relationLoader = null;
        this.relationVisitor = relationVisitor;
        this.visitorContext = null;
    }

    public ReactiveRepository(Driver driver, String label, EntityMapper<T> entityMapper,
            ReactiveRepositoryRegistry reactiveRegistry, ReactiveRelationLoader<T> relationLoader,
            ReactiveRelationVisitor relationVisitor) {
        this.driver = driver;
        this.label = label;
        this.entityMapper = entityMapper;
        this.reactiveRegistry = reactiveRegistry;
        this.relationLoader = relationLoader;
        this.relationVisitor = relationVisitor;
        this.visitorContext = null;
    }

    private static Function<ReactiveSession, Uni<Void>> closeSession() {
        return session -> Uni.createFrom().publisher(session.close())
                .replaceWithVoid()
                .onFailure().invoke(throwable -> {
                    System.err.println("Failed to close Neo4j session: " + throwable.getMessage());
                });
    }

    protected abstract Class<T> getEntityType();

    // ----------------------------------------------------------
    // Public Repository API
    // ----------------------------------------------------------

    public Uni<T> findById(Object id) {
        resetVisitor();
        return runReadQuerySingle("MATCH (n:" + label + " {id: $id}) RETURN n", Map.of("id", id))
                .flatMap(this::loadRelations);
    }

    public Multi<T> findAll() {
        resetVisitor();
        return runReadQuery("MATCH (n:" + label + ") RETURN n", Map.of())
                .onItem().transformToUniAndMerge(this::loadRelations);
    }

    public Multi<T> findAll(Pageable pageable, Sortable sortable) {
        resetVisitor();
        String sortClause = (sortable != null) ? sortable.toCypher("n") : "";
        String cypher = String.format("MATCH (n:%s) RETURN n %s SKIP $skip LIMIT $limit", label, sortClause);
        Map<String, Object> params = Map.of(
                "skip", pageable.page() * pageable.size(),
                "limit", pageable.size());
        return runReadQuery(cypher, params)
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
        Uni<List<T>> contentUni = runReadQuery(cypher, params)
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
        return runWriteQueryVoid(cypher, parameters);
    }

    public Uni<T> executeReturning(String cypher, Map<String, Object> parameters) {
        resetVisitor();
        return runWriteQuerySingle(cypher, parameters)
                .flatMap(this::loadRelations);
    }

    public Multi<T> executeQuery(String cypher, Map<String, Object> parameters) {
        resetVisitor();
        return runQueryInternal(cypher, parameters, false)
                .map(r -> entityMapper.map(r, resolveAlias(r)))
                .onItem().transformToUniAndMerge(this::loadRelations);
    }

    public Uni<Long> count() {
        return runScalarReadQuery("MATCH (n:" + label + ") RETURN count(n) AS count", Map.of(),
                r -> r.get("count").asLong());
    }

    public Uni<T> create(T entity) {
        resetVisitor();
        EntityWithRelations data = entityMapper.toDb(entity);
        Object id = entityMapper.getNodeId(entity);

        return runWriteQuerySingle("CREATE (n:" + label + " $props) RETURN n",
                Map.of("props", data.getProperties()))
                .flatMap(saved -> persistRelationships(label, id, data.getRelationships()).replaceWith(saved));
    }

    private Uni<T> createInternal(EntityWithRelations entity) {
        resetVisitor();

        String idProp = entityMapper.getNodeIdPropertyName();

        String cypher = "MERGE (n:" + label + " {" + idProp + ": $props." + idProp + "}) " +
                "SET n += $props " +
                "RETURN n";

        return runWriteQuerySingle(cypher, Map.of("props", entity.getProperties()))
                .flatMap(saved -> persistRelationships(
                        label,
                        entityMapper.getNodeId(saved),
                        entity.getRelationships())
                        .replaceWith(saved));
    }

    public Uni<T> update(T entity) {
        resetVisitor();
        Object id = entityMapper.getNodeId(entity);
        EntityWithRelations data = entityMapper.toDb(entity);

        return runWriteQuerySingle("MATCH (n:" + label + " {id: $id}) SET n += $props RETURN n",
                Map.of("id", id, "props", data.getProperties()))
                .flatMap(updated -> persistRelationships(label, id, data.getRelationships()).replaceWith(updated));
    }

    public Uni<T> merge(T entity) {
        resetVisitor();
        Object id = entityMapper.getNodeId(entity);
        EntityWithRelations data = entityMapper.toDb(entity);

        return runWriteQuerySingle("MERGE (n:" + label + " {id: $id}) SET n += $props RETURN n",
                Map.of("id", id, "props", data.getProperties()))
                .flatMap(merged -> persistRelationships(label, id, data.getRelationships()).replaceWith(merged));
    }

    public Uni<Void> delete(T entity) {
        return deleteById(entityMapper.getNodeId(entity));
    }

    public Uni<Void> deleteById(Object id) {
        resetVisitor();
        return runWriteQueryVoid("MATCH (n:" + label + " {id: $id}) DELETE n", Map.of("id", id));
    }

    public Uni<Boolean> existsById(Object id) {
        return runScalarReadQuery("MATCH (n:" + label + " {id: $id}) RETURN count(n) > 0 AS exists",
                Map.of("id", id), r -> r.get("exists").asBoolean());
    }

    public Uni<Boolean> exists(T entity) {
        return existsById(entityMapper.getNodeId(entity));
    }

    public Multi<T> query(String cypher) {
        resetVisitor();
        return runReadQuery(cypher, Map.of())
                .onItem().transformToUniAndMerge(this::loadRelations);
    }

    public Multi<T> query(String cypher, Map<String, Object> params) {
        resetVisitor();
        return runReadQuery(cypher, params)
                .onItem().transformToUniAndMerge(this::loadRelations);
    }

    public <R> Uni<R> queryScalar(String cypher, Function<Record, R> mapper) {
        return queryScalar(cypher, Map.of(), mapper);
    }

    public <R> Uni<R> queryScalar(String cypher,
            Map<String, Object> parameters,
            Function<Record, R> mapper) {
        return runScalarReadQuery(cypher, parameters, mapper);
    }

    public Uni<Paged<T>> queryPaged(
            String baseCypher,
            Map<String, Object> params,
            Pageable pageable,
            Sortable sortable) {
        resetVisitor();
        String sortClause = (sortable != null) ? sortable.toCypher("n") : "";
        String pagedCypher = baseCypher + " RETURN n " + sortClause + " SKIP $skip LIMIT $limit";
        Map<String, Object> allParams = new java.util.HashMap<>(params);
        allParams.put("skip", pageable.page() * pageable.size());
        allParams.put("limit", pageable.size());

        Uni<List<T>> contentUni = runReadQuery(pagedCypher, allParams)
                .onItem().transformToUniAndMerge(this::loadRelations)
                .collect().asList();

        String countCypher = baseCypher + " RETURN count(n) AS count";
        Uni<Long> countUni = runScalarReadQuery(countCypher, params, r -> r.get("count").asLong());

        return Uni.combine().all().unis(contentUni, countUni)
                .asTuple()
                .map(tuple -> new Paged<>(
                        tuple.getItem1(),
                        tuple.getItem2(),
                        pageable.page(),
                        pageable.size()));
    }

    public Uni<T> querySingle(String cypher) {
        return querySingle(cypher, Map.of());
    }

    public Uni<T> querySingle(String cypher, Map<String, Object> params) {
        resetVisitor();
        return runReadQuerySingle(cypher, params)
                .flatMap(this::loadRelations);
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
                            .replaceWith(() -> {
                                Class<?> entityClass = entity.getClass();
                                ReactiveRepository<Object> repo = (ReactiveRepository<Object>) reactiveRegistry
                                        .getReactiveRepository(entityClass);
                                if (repo != null && repo.getRelationLoader() != null) {
                                    return repo.getRelationLoader().loadRelations(entity, currentDepth, repo.visitorContext);
                                }
                                return Uni.createFrom().item(entity);
                            });
                });
    }

    // ----------------------------------------------------------
    // Visitor & Monitoring
    // ----------------------------------------------------------

    private void resetVisitor() {
        if (relationVisitor != null) {
            this.visitorContext = relationVisitor.newContext();
        }
    }

    public ReactiveRelationVisitor.VisitorStats getVisitorStats() {
        return visitorContext != null ? visitorContext.getStats() : null;
    }

    public List<ReactiveRelationVisitor.TraversalStep> getTraversalPath() {
        return visitorContext != null ? visitorContext.traversalPath : List.of();
    }

    // ----------------------------------------------------------
    // Internals: query execution with alias resolution
    // ----------------------------------------------------------

    private Multi<T> runReadQuery(String cypher, Map<String, Object> params) {
        return runQueryInternal(cypher, params, true)
                .map(r -> entityMapper.map(r, resolveAlias(r)));
    }

    private Uni<T> runReadQuerySingle(String cypher, Map<String, Object> params) {
        return runReadQuery(cypher, params).toUni();
    }

    private Uni<T> runWriteQuerySingle(String cypher, Map<String, Object> params) {
        return runQueryInternal(cypher, params, false)
                .collect().asList()
                .map(records -> records.isEmpty() ? null : entityMapper.map(records.get(0), resolveAlias(records.get(0))));
    }

    private Uni<Void> runWriteQueryVoid(String cypher, Map<String, Object> params) {
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
                .onFailure().transform(throwable -> new RepositoryException("Failed to execute write query", throwable));
    }

    private <R> Uni<R> runScalarReadQuery(String cypher, Map<String, Object> params, Function<Record, R> mapper) {
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
                .onFailure().transform(throwable -> new RepositoryException("Failed to execute scalar query", throwable));
    }

    private Multi<Record> runQueryInternal(String cypher, Map<String, Object> params, boolean readOnly) {
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
                .onFailure().transform(throwable -> new RepositoryException("Failed to execute query", throwable));
    }

    private Uni<Void> persistRelationships(String label, Object fromId, List<RelationshipData> relationships) {
        if (relationships == null || relationships.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        return Multi.createFrom().iterable(relationships)
                .onItem().transformToUniAndMerge(rel -> {
                    if (rel.getTarget() != null) {
                        EntityWithRelations target = rel.getTarget();

                        @SuppressWarnings("unchecked")
                        ReactiveRepository<Object> targetRepo = (ReactiveRepository<Object>) reactiveRegistry
                                .getReactiveRepository(target.getEntityType());

                        return targetRepo.createInternal(target)
                                .map(saved -> targetRepo.getEntityMapper().getNodeId(saved))
                                .invoke(rel::setTargetId)
                                .call(id -> persistRelationships(
                                        target.getEntityType().getSimpleName(),
                                        id,
                                        target.getRelationships()));
                    }
                    return Uni.createFrom().voidItem();
                })
                .collect().asList()
                .flatMap(ignore -> Multi.createFrom().iterable(relationships)
                        .onItem().transformToUniAndMerge(rel -> {
                            Object toId = rel.getTargetId();
                            if (toId == null) {
                                return Uni.createFrom().voidItem();
                            }
                            String query = switch (rel.getDirection()) {
                                case OUTGOING ->
                                    "MATCH (a:" + label + " {id: $from}), (b {id: $to}) MERGE (a)-[r:" + rel.getType()
                                            + "]->(b)";
                                case INCOMING ->
                                    "MATCH (a:" + label + " {id: $from}), (b {id: $to}) MERGE (a)<-[r:" + rel.getType()
                                            + "]-(b)";
                                default ->
                                    throw new UnsupportedOperationException("Unsupported direction: " + rel.getDirection());
                            };
                            return runWriteQueryVoid(query, Map.of("from", fromId, "to", toId.toString()));
                        })
                        .collect().asList()
                        .replaceWithVoid());
    }

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
