package de.prgrm.quarkus.neo4j.ogm.it.model;

import java.util.Objects;

/**
 * Custom type for application roles.
 * Demonstrates context-aware converter with non-String field types.
 */
public class ApplicationRole {

    private final String role;
    private final String discriminator;

    public ApplicationRole(String role, String discriminator) {
        this.role = role;
        this.discriminator = discriminator;
    }

    public String getRole() {
        return role;
    }

    public String getDiscriminator() {
        return discriminator;
    }

    public String getFullRole() {
        return discriminator != null ? role + "_" + discriminator : role;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ApplicationRole that = (ApplicationRole) o;
        return Objects.equals(role, that.role) && Objects.equals(discriminator, that.discriminator);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, discriminator);
    }

    @Override
    public String toString() {
        return getFullRole();
    }
}
