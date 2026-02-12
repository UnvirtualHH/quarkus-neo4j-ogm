package de.prgrm.quarkus.neo4j.ogm.it.converter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import de.prgrm.quarkus.neo4j.ogm.it.model.Application;
import de.prgrm.quarkus.neo4j.ogm.it.model.ApplicationBaseRepository;
import de.prgrm.quarkus.neo4j.ogm.it.model.ApplicationRole;
import de.prgrm.quarkus.neo4j.ogm.it.model.UserApplication;
import de.prgrm.quarkus.neo4j.ogm.it.model.UserApplicationBaseRepository;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests for Issue #48, Comment 2 (Feb 12, 2026):
 * Context-aware converters must work correctly with multiple query results.
 * The bug was that _rawValues was shared across all entities, causing
 * converters to receive wrong values from the last mapped entity.
 */
@QuarkusTest
class MultipleResultsContextAwareConverterTest {

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
    void testContextAwareConverterWithMultipleResults() {
        // Create two different applications with different discriminators
        Application webApp = new Application();
        webApp.setName("WebApp");
        webApp.setDiscriminator("WEB");
        webApp = applicationRepository.create(webApp);

        Application mobileApp = new Application();
        mobileApp.setName("MobileApp");
        mobileApp.setDiscriminator("MOBILE");
        mobileApp = applicationRepository.create(mobileApp);

        // Create two UserApplications with different roles pointing to different apps
        UserApplication userApp1 = new UserApplication();
        userApp1.setApplication(webApp);
        userApp1.setRole(new ApplicationRole("ADMIN", "WEB"));
        userApp1 = userApplicationRepository.create(userApp1);
        final java.util.UUID id1 = userApp1.getId();

        UserApplication userApp2 = new UserApplication();
        userApp2.setApplication(mobileApp);
        userApp2.setRole(new ApplicationRole("VIEWER", "MOBILE"));
        userApp2 = userApplicationRepository.create(userApp2);
        final java.util.UUID id2 = userApp2.getId();

        // Retrieve all UserApplications in a single query
        // This is where the bug was: _rawValues was shared and only held the last entity's data
        List<UserApplication> allUserApps = userApplicationRepository.findAll();

        assertEquals(2, allUserApps.size(), "Should retrieve both UserApplications");

        // Find each UserApplication by ID
        UserApplication retrieved1 = allUserApps.stream()
                .filter(ua -> ua.getId().equals(id1))
                .findFirst()
                .orElseThrow();

        UserApplication retrieved2 = allUserApps.stream()
                .filter(ua -> ua.getId().equals(id2))
                .findFirst()
                .orElseThrow();

        // Verify first UserApplication has correct role with WEB discriminator
        assertNotNull(retrieved1.getRole(), "First UserApplication should have a role");
        assertEquals("ADMIN", retrieved1.getRole().getRole());
        assertEquals("WEB", retrieved1.getRole().getDiscriminator());
        assertEquals("ADMIN_WEB", retrieved1.getRole().getFullRole());

        // Verify second UserApplication has correct role with MOBILE discriminator
        // This would fail with the old bug because _rawValues only had data from the last entity
        assertNotNull(retrieved2.getRole(), "Second UserApplication should have a role");
        assertEquals("VIEWER", retrieved2.getRole().getRole());
        assertEquals("MOBILE", retrieved2.getRole().getDiscriminator());
        assertEquals("VIEWER_MOBILE", retrieved2.getRole().getFullRole());
    }

    @Test
    void testContextAwareConverterWithThreeResults() {
        // Create three different applications
        Application webApp = new Application();
        webApp.setName("WebApp");
        webApp.setDiscriminator("WEB");
        webApp = applicationRepository.create(webApp);

        Application mobileApp = new Application();
        mobileApp.setName("MobileApp");
        mobileApp.setDiscriminator("MOBILE");
        mobileApp = applicationRepository.create(mobileApp);

        Application desktopApp = new Application();
        desktopApp.setName("DesktopApp");
        desktopApp.setDiscriminator("DESKTOP");
        desktopApp = applicationRepository.create(desktopApp);

        // Create three UserApplications
        UserApplication userApp1 = new UserApplication();
        userApp1.setApplication(webApp);
        userApp1.setRole(new ApplicationRole("ADMIN", "WEB"));
        userApp1 = userApplicationRepository.create(userApp1);

        UserApplication userApp2 = new UserApplication();
        userApp2.setApplication(mobileApp);
        userApp2.setRole(new ApplicationRole("EDITOR", "MOBILE"));
        userApp2 = userApplicationRepository.create(userApp2);

        UserApplication userApp3 = new UserApplication();
        userApp3.setApplication(desktopApp);
        userApp3.setRole(new ApplicationRole("VIEWER", "DESKTOP"));
        userApp3 = userApplicationRepository.create(userApp3);

        // Retrieve all at once
        List<UserApplication> allUserApps = userApplicationRepository.findAll();
        assertEquals(3, allUserApps.size());

        // Verify each has the correct role
        for (UserApplication ua : allUserApps) {
            assertNotNull(ua.getRole());
            assertNotNull(ua.getApplication());

            // The converter should have used the correct application's discriminator
            assertEquals(ua.getApplication().getDiscriminator(),
                    ua.getRole().getDiscriminator(),
                    "Role discriminator should match application discriminator");

            // Verify full role is correctly composed
            String expectedFullRole = ua.getRole().getRole() + "_" + ua.getApplication().getDiscriminator();
            assertEquals(expectedFullRole, ua.getRole().getFullRole());
        }
    }
}
