package de.prgrm.quarkus.neo4j.ogm.runtime.processor.types;

public class StringTypeHandler extends AbstractSimpleTypeHandler {
    @Override
    protected String getSupportedType() {
        return "java.lang.String";
    }

    @Override
    protected String readMethod() {
        return "asString";
    }
}
