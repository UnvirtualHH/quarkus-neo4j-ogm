package de.prgrm.quarkus.neo4j.ogm.runtime.repository;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

import de.prgrm.quarkus.neo4j.ogm.runtime.enums.Direction;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.Relationship;
import io.smallrye.mutiny.Uni;

/**
 * Reactive visitor that manages entity relationship traversal without ThreadLocal.
 * Context is explicitly carried through the reactive pipeline.
 */
@ApplicationScoped
public class ReactiveRelationVisitor {

    private static final Map<Class<?>, List<RelationshipInfo>> RELATIONSHIP_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, IdAccessor> ID_ACCESSOR_CACHE = new ConcurrentHashMap<>();
    private static final int DEFAULT_MAX_DEPTH = 5;

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

    public Uni<Boolean> shouldLoadRelationship(Object entity, String fieldName, int currentDepth, VisitorContext ctx) {
        List<RelationshipInfo> relationships = getRelationshipInfo(entity.getClass());

        for (RelationshipInfo info : relationships) {
            if (info.fieldName.equals(fieldName)) {
                if (currentDepth >= info.maxDepth) {
                    ctx.stats.depthLimitHits++;
                    return Uni.createFrom().item(false);
                }
                return Uni.createFrom().item(true);
            }
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

    /**
     * Functional interface for cached ID access
     */
    @FunctionalInterface
    private interface IdAccessor {
        Object extractId(Object entity) throws Exception;
    }

    private Object extractEntityId(Object entity) {
        if (entity == null)
            return null;

        try {
            Class<?> clazz = entity.getClass();

            // Use cached accessor for performance
            IdAccessor accessor = ID_ACCESSOR_CACHE.computeIfAbsent(clazz, c -> {
                // Try common ID field names
                for (String fieldName : new String[] { "id", "ID", "_id", "entityId" }) {
                    try {
                        Field field = c.getDeclaredField(fieldName);
                        field.setAccessible(true);
                        return field::get; // Cache field accessor
                    } catch (NoSuchFieldException ignored) {
                    }
                }

                // Try getId() method
                try {
                    var method = c.getMethod("getId");
                    return method::invoke; // Cache method accessor
                } catch (NoSuchMethodException ignored) {
                }

                // Return null accessor if no ID field/method found
                return e -> null;
            });

            return accessor.extractId(entity);

        } catch (Exception e) {
            // ignore
        }

        return null;
    }

    private static List<RelationshipInfo> getRelationshipInfo(Class<?> entityClass) {
        return RELATIONSHIP_CACHE.computeIfAbsent(entityClass, clazz -> {
            List<RelationshipInfo> relationships = new ArrayList<>();
            for (Field field : clazz.getDeclaredFields()) {
                Relationship rel = field.getAnnotation(Relationship.class);
                if (rel != null) {
                    relationships.add(new RelationshipInfo(
                            field.getName(),
                            rel.maxDepth(),
                            rel.type(),
                            rel.direction()));
                }
            }
            return relationships;
        });
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

    private static class RelationshipInfo {
        final String fieldName;
        final int maxDepth;
        final String type;
        final Direction direction;

        RelationshipInfo(String fieldName, int maxDepth, String type,
                Direction direction) {
            this.fieldName = fieldName;
            this.maxDepth = maxDepth;
            this.type = type;
            this.direction = direction;
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