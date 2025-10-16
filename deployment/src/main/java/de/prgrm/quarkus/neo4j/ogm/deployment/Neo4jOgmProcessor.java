package de.prgrm.quarkus.neo4j.ogm.deployment;

import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.EntityMapperRegistry;
import de.prgrm.quarkus.neo4j.ogm.runtime.repository.ReactiveRelationVisitor;
import de.prgrm.quarkus.neo4j.ogm.runtime.repository.ReactiveRepositoryRegistry;
import de.prgrm.quarkus.neo4j.ogm.runtime.repository.RelationVisitor;
import de.prgrm.quarkus.neo4j.ogm.runtime.repository.RepositoryRegistry;
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
        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(RelationVisitor.class));
        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(ReactiveRelationVisitor.class));
        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(EntityMapperRegistry.class));
        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(RepositoryRegistry.class));
        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(ReactiveRepositoryRegistry.class));
    }
}
