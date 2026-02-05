package de.prgrm.quarkus.neo4j.ogm.it.model;

import de.prgrm.quarkus.neo4j.ogm.runtime.converter.ContextAwareAttributeConverter;

/**
 * Converter that uses the related Application entity to read the discriminator.
 * This demonstrates the context-aware converter feature.
 */
public class ApplicationRoleConverter implements ContextAwareAttributeConverter<String, String, UserApplication> {

    @Override
    public String toGraphProperty(String value, UserApplication entity) {
        // With context-aware converter, we don't need to store discriminator in the role
        // The discriminator is accessible through the related Application entity
        return value;
    }

    @Override
    public String toEntityAttribute(String value, UserApplication entity) {
        // Access the discriminator from the related Application entity
        if (entity != null && entity.getApplication() != null) {
            String discriminator = entity.getApplication().getDiscriminator();
            // Parse role based on discriminator (example logic)
            return discriminator != null ? value + "_" + discriminator : value;
        }
        return value;
    }
}
