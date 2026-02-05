package de.prgrm.quarkus.neo4j.ogm.runtime.converter;

/**
 * Extended attribute converter that provides access to the owning entity.
 * This allows converters to read related entity attributes or discriminator values.
 *
 * <p>
 * Example usage:
 * </p>
 *
 * <pre>
 * public class ApplicationRoleConverter implements ContextAwareAttributeConverter&lt;ApplicationRole, String, UserApplication&gt; {
 *
 *     &#64;Override
 *     public String toGraphProperty(ApplicationRole value, UserApplication entity) {
 *         // Can access related entities through the entity parameter
 *         return value.write();
 *     }
 *
 *     &#64;Override
 *     public ApplicationRole toEntityAttribute(String value, UserApplication entity) {
 *         // Can read discriminator from related Application entity
 *         String discriminator = entity.getApplication().getDiscriminator();
 *         return ApplicationRole.parse(value, discriminator);
 *     }
 * }
 * </pre>
 *
 * @param <EntityType> the entity attribute type
 * @param <GraphType> the graph property type (typically String)
 * @param <OwnerType> the type of the entity that owns this field
 */
public interface ContextAwareAttributeConverter<EntityType, GraphType, OwnerType>
        extends AttributeConverter<EntityType, GraphType> {

    /**
     * Converts an entity attribute value to a graph property value,
     * with access to the owning entity for context.
     *
     * @param value the entity attribute value
     * @param entity the entity that owns this field
     * @return the graph property value
     */
    GraphType toGraphProperty(EntityType value, OwnerType entity);

    /**
     * Converts a graph property value to an entity attribute value,
     * with access to the owning entity for context.
     *
     * @param value the graph property value
     * @param entity the entity that owns this field
     * @return the entity attribute value
     */
    EntityType toEntityAttribute(GraphType value, OwnerType entity);

    /**
     * Default implementation - delegates to context-aware version with null entity.
     * This should not be called in normal operation as the generated code will
     * use the context-aware version.
     */
    @Override
    default GraphType toGraphProperty(EntityType value) {
        return toGraphProperty(value, null);
    }

    /**
     * Default implementation - delegates to context-aware version with null entity.
     * This should not be called in normal operation as the generated code will
     * use the context-aware version.
     */
    @Override
    default EntityType toEntityAttribute(GraphType value) {
        return toEntityAttribute(value, null);
    }
}
