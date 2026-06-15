package de.prgrm.quarkus.neo4j.ogm.runtime.repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.EntityMapper;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.EntityMapperRegistry;
import io.smallrye.mutiny.Uni;

/**
 * Reactive visitor that manages entity relationship traversal without ThreadLocal.
 * Context is explicitly carried through the reactive pipeline.
 */
@ApplicationScoped
public class ReactiveRelationVisitor {

    private static final int DEFAULT_MAX_DEPTH = 5;

    /**
     * Used to extract entity ids without reflection, via the generated mappers.
     */
    @Inject
    EntityMapperRegistry mapperRegistry;

    public ReactiveRelationVisitor() {
    }

    // -------------------- API methods --------------------

    public Uni<Boolean> shouldVisit(Object entity, int currentDepth, VisitorContext ctx) {
        if (entity == null) {
            return Uni.createFrom().item(false);
        }

        Object entityId = extractEntityId(entity);

        // Depth limit
        if (currentDepth > ctx.maxDepth) {
            ctx.stats.depthLimitHits++;
            return Uni.createFrom().item(false);
        }

        // Circular by ID
        if (entityId != null && ctx.visitedIds.contains(entityId)) {
            ctx.stats.circularReferencesPrevented++;
            return Uni.createFrom().item(false);
        }

        // Circular by identity
        if (entityId == null) {
            IdentityWrapper wrapper = new IdentityWrapper(entity);
            if (ctx.visitedObjects.contains(wrapper)) {
                ctx.stats.circularReferencesPrevented++;
                return Uni.createFrom().item(false);
            }
        }

        return Uni.createFrom().item(true);
    }

    /**
     * The {@code maxDepth} is supplied by the generated relation loader (known at compile time),
     * so no runtime reflection is needed to read the {@code @Relationship} annotation.
     */
    public Uni<Boolean> shouldLoadRelationship(int currentDepth, int maxDepth, VisitorContext ctx) {
        if (currentDepth >= maxDepth) {
            ctx.stats.depthLimitHits++;
            return Uni.createFrom().item(false);
        }
        return Uni.createFrom().item(true);
    }

    public Uni<Void> markVisited(Object entity, VisitorContext ctx) {
        if (entity == null) {
            return Uni.createFrom().voidItem();
        }

        Object entityId = extractEntityId(entity);
        if (entityId != null) {
            ctx.visitedIds.add(entityId);
        } else {
            ctx.visitedObjects.add(new IdentityWrapper(entity));
        }

        ctx.stats.entitiesVisited++;
        ctx.traversalPath.add(new TraversalStep(
                entity.getClass().getSimpleName(),
                entityId,
                ctx.traversalPath.size()));
        return Uni.createFrom().voidItem();
    }

    public Uni<Boolean> wasVisited(Object entity, VisitorContext ctx) {
        if (entity == null) {
            return Uni.createFrom().item(false);
        }
        Object entityId = extractEntityId(entity);
        if (entityId != null) {
            return Uni.createFrom().item(ctx.visitedIds.contains(entityId));
        }
        return Uni.createFrom().item(ctx.visitedObjects.contains(new IdentityWrapper(entity)));
    }

    /**
     * Mark an entity as persisted (for cycle prevention during persist operations).
     * Returns true if entity was newly marked, false if already marked.
     */
    public Uni<Boolean> markPersisted(String label, Object id, VisitorContext ctx) {
        if (id == null) {
            return Uni.createFrom().item(false);
        }
        String key = label + ":" + id.toString();
        boolean added = ctx.persistedEntities.add(key);
        return Uni.createFrom().item(added);
    }

    /**
     * Check if entity was already persisted.
     */
    public Uni<Boolean> wasPersisted(String label, Object id, VisitorContext ctx) {
        if (id == null) {
            return Uni.createFrom().item(false);
        }
        String key = label + ":" + id.toString();
        return Uni.createFrom().item(ctx.persistedEntities.contains(key));
    }

    // -------------------- Context factory --------------------

    public VisitorContext newContext() {
        return new VisitorContext(DEFAULT_MAX_DEPTH);
    }

    // -------------------- Helpers --------------------

    @SuppressWarnings("unchecked")
    private Object extractEntityId(Object entity) {
        if (entity == null) {
            return null;
        }
        // Resolve the id via the generated mapper (no reflection). Falls back to identity-based
        // tracking when no mapper is registered for the type.
        EntityMapper<Object> mapper = (mapperRegistry != null)
                ? (EntityMapper<Object>) mapperRegistry.find((Class<Object>) entity.getClass())
                : null;
        return (mapper != null) ? mapper.getNodeId(entity) : null;
    }

    // -------------------- Inner classes --------------------

    public static class VisitorContext {
        final Set<Object> visitedIds = ConcurrentHashMap.newKeySet();
        final Set<IdentityWrapper> visitedObjects = ConcurrentHashMap.newKeySet();
        final List<TraversalStep> traversalPath = new ArrayList<>();
        final VisitorStats stats = new VisitorStats();
        final Set<String> persistedEntities = ConcurrentHashMap.newKeySet();
        int maxDepth;

        public VisitorContext(int maxDepth) {
            this.maxDepth = maxDepth;
        }

        public VisitorStats getStats() {
            return stats.copy();
        }
    }

    private static class IdentityWrapper {
        private final Object obj;
        private final int identityHashCode;

        IdentityWrapper(Object obj) {
            this.obj = obj;
            this.identityHashCode = System.identityHashCode(obj);
        }

        @Override
        public boolean equals(Object other) {
            if (other == null || getClass() != other.getClass()) {
                return false;
            }
            return ((IdentityWrapper) other).obj == obj;
        }

        @Override
        public int hashCode() {
            return identityHashCode;
        }
    }

    public static class TraversalStep {
        private final String entityType;
        private final Object entityId;
        private final int depth;
        private final long timestamp;

        public TraversalStep(String entityType, Object entityId, int depth) {
            this.entityType = entityType;
            this.entityId = entityId;
            this.depth = depth;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public static class VisitorStats {
        private int entitiesVisited = 0;
        private int circularReferencesPrevented = 0;
        private int depthLimitHits = 0;

        void reset() {
            entitiesVisited = 0;
            circularReferencesPrevented = 0;
            depthLimitHits = 0;
        }

        VisitorStats copy() {
            VisitorStats copy = new VisitorStats();
            copy.entitiesVisited = this.entitiesVisited;
            copy.circularReferencesPrevented = this.circularReferencesPrevented;
            copy.depthLimitHits = this.depthLimitHits;
            return copy;
        }
    }
}