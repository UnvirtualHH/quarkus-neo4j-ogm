package io.quarkiverse.quarkus.neo4j.ogm.deployment;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.repository.ReactiveRepositoryRegistry;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.repository.RepositoryRegistry;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

class Neo4jOgmProcessor {

    private static final String FEATURE = "neo4j-ogm";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void registerBeans(BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        additionalBeans.produce(new AdditionalBeanBuildItem(RepositoryRegistry.class));
        additionalBeans.produce(new AdditionalBeanBuildItem(ReactiveRepositoryRegistry.class));
    }
}
