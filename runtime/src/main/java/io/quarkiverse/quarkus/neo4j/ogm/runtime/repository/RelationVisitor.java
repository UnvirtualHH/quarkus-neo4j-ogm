package io.quarkiverse.quarkus.neo4j.ogm.runtime.repository;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.Relationship;

/**
 * Application-scoped visitor that manages entity relationship traversal using ThreadLocal.
 * This approach is compatible with @ApplicationScoped repositories and relation loaders.
 */
@ApplicationScoped
public class RelationVisitor {

    /**
     * ThreadLocal context for each traversal operation (thread-safe)
     */
    private static final ThreadLocal<VisitorContext> CONTEXT = ThreadLocal.withInitial(VisitorContext::new);

    /**
     * Cache for relationship metadata per entity class
     */
    private static final Map<Class<?>, List<RelationshipInfo>> RELATIONSHIP_CACHE = new ConcurrentHashMap<>();

    /**
     * CDI constructor
     */
    public RelationVisitor() {
        // CDI will handle instantiation
    }

    /**
     * Check if an entity should be visited based on depth and circular reference rules
     */
    public boolean shouldVisit(Object entity, int currentDepth) {
        if (entity == null) {
            return false;
        }

        VisitorContext context = CONTEXT.get();
        Object entityId = extractEntityId(entity);

        // Check circular references
        if (entityId != null && context.visitedIds.contains(entityId)) {
            context.stats.circularReferencesPrevented++;
            return false;
        }

        // Fallback to object identity if no ID
        if (entityId == null) {
            IdentityWrapper wrapper = new IdentityWrapper(entity);
            if (context.visitedObjects.contains(wrapper)) {
                context.stats.circularReferencesPrevented++;
                return false;
            }
        }

        return true;
    }

    /**
     * Check if a specific relationship should be loaded based on its depth annotation
     */
    public boolean shouldLoadRelationship(Object entity, String fieldName, int currentDepth) {
        List<RelationshipInfo> relationships = getRelationshipInfo(entity.getClass());

        for (RelationshipInfo info : relationships) {
            if (info.fieldName.equals(fieldName)) {
                if (currentDepth >= info.maxDepth) {
                    CONTEXT.get().stats.depthLimitHits++;
                    return false;
                }
                return true;
            }
        }

        return true; // Default to true if no specific depth limit found
    }

    /**
     * Mark an entity as visited
     */
    public void markVisited(Object entity) {
        if (entity == null)
            return;

        VisitorContext context = CONTEXT.get();
        Object entityId = extractEntityId(entity);

        if (entityId != null) {
            context.visitedIds.add(entityId);
        } else {
            context.visitedObjects.add(new IdentityWrapper(entity));
        }

        context.stats.entitiesVisited++;

        // Track traversal path for debugging
        context.traversalPath.add(new TraversalStep(
                entity.getClass().getSimpleName(),
                entityId,
                context.traversalPath.size()));
    }

    /**
     * Check if entity was already visited
     */
    public boolean wasVisited(Object entity) {
        if (entity == null)
            return false;

        VisitorContext context = CONTEXT.get();
        Object entityId = extractEntityId(entity);
        if (entityId != null) {
            return context.visitedIds.contains(entityId);
        }

        return context.visitedObjects.contains(new IdentityWrapper(entity));
    }

    /**
     * Get current traversal depth
     */
    public int getCurrentDepth() {
        return CONTEXT.get().traversalPath.size();
    }

    /**
     * Get traversal path (for debugging)
     */
    public List<TraversalStep> getTraversalPath() {
        return new ArrayList<>(CONTEXT.get().traversalPath);
    }

    /**
     * Get visitor statistics
     */
    public VisitorStats getStats() {
        return CONTEXT.get().stats.copy();
    }

    /**
     * Reset visitor state (called after each repository operation)
     */
    public void reset() {
        VisitorContext context = CONTEXT.get();
        context.visitedIds.clear();
        context.visitedObjects.clear();
        context.traversalPath.clear();
        context.stats.reset();
    }

    /**
     * Clear ThreadLocal context to prevent memory leaks
     */
    public void clearContext() {
        CONTEXT.remove();
    }

    // ========================= Private Helper Methods =========================

    /**
     * Extract entity ID using common patterns
     */
    private Object extractEntityId(Object entity) {
        if (entity == null)
            return null;

        try {
            Class<?> clazz = entity.getClass();

            // Try common ID field names
            String[] idFieldNames = { "id", "ID", "_id", "entityId" };
            for (String fieldName : idFieldNames) {
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    return field.get(entity);
                } catch (NoSuchFieldException ignored) {
                    // Try next field name
                }
            }

            // Try getId method
            try {
                return clazz.getMethod("getId").invoke(entity);
            } catch (NoSuchMethodException ignored) {
                // No getId method
            }

        } catch (Exception e) {
            // Fall back to null
        }

        return null;
    }

    /**
     * Get relationship information for an entity class (cached)
     */
    private static List<RelationshipInfo> getRelationshipInfo(Class<?> entityClass) {
        return RELATIONSHIP_CACHE.computeIfAbsent(entityClass, clazz -> {
            List<RelationshipInfo> relationships = new ArrayList<>();

            // Scan all fields for @Relationship annotations
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
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

    // ========================= Inner Classes =========================

    /**
     * Thread-local context for visitor state
     */
    private static class VisitorContext {
        final Set<Object> visitedIds = ConcurrentHashMap.newKeySet();
        final Set<IdentityWrapper> visitedObjects = ConcurrentHashMap.newKeySet();
        final List<TraversalStep> traversalPath = new ArrayList<>();
        final VisitorStats stats = new VisitorStats();
    }

    /**
     * Cached relationship information
     */
    private static class RelationshipInfo {
        final String fieldName;
        final int maxDepth;
        final String type;
        final io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.Direction direction;

        RelationshipInfo(String fieldName, int maxDepth, String type,
                io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.Direction direction) {
            this.fieldName = fieldName;
            this.maxDepth = maxDepth;
            this.type = type;
            this.direction = direction;
        }
    }

    /**
     * Object identity wrapper
     */
    private static class IdentityWrapper {
        private final Object obj;
        private final int identityHashCode;

        IdentityWrapper(Object obj) {
            this.obj = obj;
            this.identityHashCode = System.identityHashCode(obj);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other)
                return true;
            if (!(other instanceof IdentityWrapper))
                return false;
            IdentityWrapper that = (IdentityWrapper) other;
            return obj == that.obj; // Reference equality
        }

        @Override
        public int hashCode() {
            return identityHashCode;
        }
    }

    /**
     * Traversal step for debugging
     */
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

        public String getEntityType() {
            return entityType;
        }

        public Object getEntityId() {
            return entityId;
        }

        public int getDepth() {
            return depth;
        }

        public long getTimestamp() {
            return timestamp;
        }

        @Override
        public String toString() {
            return String.format("%s[id=%s, depth=%d]", entityType, entityId, depth);
        }
    }

    /**
     * Visitor statistics
     */
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

        public int getEntitiesVisited() {
            return entitiesVisited;
        }

        public int getCircularReferencesPrevented() {
            return circularReferencesPrevented;
        }

        public int getDepthLimitHits() {
            return depthLimitHits;
        }

        @Override
        public String toString() {
            return String.format("VisitorStats{visited=%d, circular=%d, depthLimits=%d}",
                    entitiesVisited, circularReferencesPrevented, depthLimitHits);
        }
    }
}