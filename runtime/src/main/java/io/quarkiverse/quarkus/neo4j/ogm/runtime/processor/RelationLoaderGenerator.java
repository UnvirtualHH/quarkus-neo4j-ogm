package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.GenerateRepository;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.relations.ImperativeRelationLoaderGenerator;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.relations.ReactiveRelationLoaderGenerator;

/**
 * Code generator for relation loaders (imperative and reactive).
 *
 * Generates classes that handle recursive relationship loading using
 * RelationVisitor (imperative) or ReactiveRelationVisitor (reactive).
 */
public class RelationLoaderGenerator {
    private final ImperativeRelationLoaderGenerator imperative = new ImperativeRelationLoaderGenerator();
    private final ReactiveRelationLoaderGenerator reactive = new ReactiveRelationLoaderGenerator();

    public void generateRelationLoader(String packageName,
            TypeElement entityType,
            String loaderClassName,
            ProcessingEnvironment processingEnv,
            GenerateRepository.RepositoryType repoType) {
        switch (repoType) {
            case BLOCKING -> imperative.generateRelationLoader(packageName, entityType, loaderClassName, processingEnv);
            case REACTIVE -> reactive.generateRelationLoader(packageName, entityType, loaderClassName, processingEnv);
            case BOTH -> {
                imperative.generateRelationLoader(packageName, entityType,
                        entityType.getSimpleName() + "RelationLoader", processingEnv);
                reactive.generateRelationLoader(packageName, entityType,
                        entityType.getSimpleName() + "ReactiveRelationLoader", processingEnv);
            }
        }
    }
}
