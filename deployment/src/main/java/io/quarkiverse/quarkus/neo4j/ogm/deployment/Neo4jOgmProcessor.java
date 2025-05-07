package io.quarkiverse.quarkus.neo4j.ogm.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

class Neo4jOgmProcessor {

    private static final String FEATURE = "neo4j-ogm";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }
}
