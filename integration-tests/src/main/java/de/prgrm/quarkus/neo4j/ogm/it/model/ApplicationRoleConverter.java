package de.prgrm.quarkus.neo4j.ogm.it.model;

import de.prgrm.quarkus.neo4j.ogm.runtime.converter.ContextAwareAttributeConverter;

/**
 * Converter that uses the related Application entity to read the discriminator.
 * This demonstrates the context-aware converter feature with custom type conversion.
 *
 * Converts between:
 * - Database: String (e.g., "EDITOR")
 * - Entity: ApplicationRole (custom object containing role + discriminator)
 */
public class ApplicationRoleConverter implements ContextAwareAttributeConverter<ApplicationRole, String, UserApplication> {

    @Override
    public String toGraphProperty(ApplicationRole value, UserApplication entity) {
        // Store only the base role value in the database
        // The discriminator will be derived from the related Application entity when loading
        return value != null ? value.getRole() : null;
    }

    @Override
    public ApplicationRole toEntityAttribute(String value, UserApplication entity) {
        // Access the discriminator from the related Application entity
        if (value == null) {
            return null;
        }

        String discriminator = null;
        if (entity != null && entity.getApplication() != null) {
            discriminator = entity.getApplication().getDiscriminator();
        }

        // Create ApplicationRole with context from related entity
        return new ApplicationRole(value, discriminator);
    }
}
