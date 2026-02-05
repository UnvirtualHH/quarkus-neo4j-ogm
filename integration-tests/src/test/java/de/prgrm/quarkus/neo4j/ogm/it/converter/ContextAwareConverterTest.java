package de.prgrm.quarkus.neo4j.ogm.it.converter;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import de.prgrm.quarkus.neo4j.ogm.it.model.Application;
import de.prgrm.quarkus.neo4j.ogm.it.model.ApplicationBaseRepository;
import de.prgrm.quarkus.neo4j.ogm.it.model.UserApplication;
import de.prgrm.quarkus.neo4j.ogm.it.model.UserApplicationBaseRepository;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests for Issue #48: Support related entity access inside custom converters
 */
@QuarkusTest
class ContextAwareConverterTest {

    @Inject
    ApplicationBaseRepository applicationRepository;

    @Inject
    UserApplicationBaseRepository userApplicationRepository;

    @AfterEach
    void cleanup() {
        // Cleanup test data
        userApplicationRepository.findAll().forEach(ua -> userApplicationRepository.delete(ua));
        applicationRepository.findAll().forEach(app -> applicationRepository.delete(app));
    }

    @Test
    void testContextAwareConverterWithRelatedEntity() {
        // Create an application with a discriminator
        Application app = new Application();
        app.setName("TestApp");
        app.setDiscriminator("WEB");
        app = applicationRepository.create(app);

        // Create a UserApplication with a role
        UserApplication userApp = new UserApplication();
        userApp.setApplication(app);
        userApp.setRole("ADMIN");
        userApp = userApplicationRepository.create(userApp);

        // Retrieve and verify
        UserApplication retrieved = userApplicationRepository.findById(userApp.getId());
        assertNotNull(retrieved);
        assertNotNull(retrieved.getApplication());
        assertEquals("WEB", retrieved.getApplication().getDiscriminator());

        // The converter should have access to the related Application
        // and enrich the role with the discriminator
        assertEquals("ADMIN_WEB", retrieved.getRole());
    }

    @Test
    void testContextAwareConverterWithNullRelation() {
        // Create a UserApplication without a related application
        UserApplication userApp = new UserApplication();
        userApp.setRole("VIEWER");
        userApp = userApplicationRepository.create(userApp);

        // Retrieve and verify
        UserApplication retrieved = userApplicationRepository.findById(userApp.getId());
        assertNotNull(retrieved);
        assertNull(retrieved.getApplication());

        // The converter should handle null gracefully
        assertEquals("VIEWER", retrieved.getRole());
    }

    @Test
    void testContextAwareConverterUpdate() {
        // Create an application
        Application app1 = new Application();
        app1.setName("App1");
        app1.setDiscriminator("MOBILE");
        app1 = applicationRepository.create(app1);

        // Create UserApplication
        UserApplication userApp = new UserApplication();
        userApp.setApplication(app1);
        userApp.setRole("EDITOR");
        userApp = userApplicationRepository.create(userApp);

        // Verify initial state
        UserApplication retrieved = userApplicationRepository.findById(userApp.getId());
        assertEquals("EDITOR_MOBILE", retrieved.getRole());

        // Create another application with different discriminator
        Application app2 = new Application();
        app2.setName("App2");
        app2.setDiscriminator("DESKTOP");
        app2 = applicationRepository.create(app2);

        // Update the UserApplication to point to app2
        retrieved.setApplication(app2);
        retrieved.setRole("VIEWER");
        userApplicationRepository.update(retrieved);

        // Retrieve again and verify the role reflects new discriminator
        UserApplication updated = userApplicationRepository.findById(userApp.getId());
        assertEquals("VIEWER_DESKTOP", updated.getRole());
    }
}
