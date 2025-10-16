package de.prgrm.quarkus.neo4j.ogm.runtime.processor.types;

public class IntegerTypeHandler extends AbstractSimpleTypeHandler {
    @Override
    protected String getSupportedType() {
        return "java.lang.Integer";
    }

    @Override
    protected String readMethod() {
        return "asInt";
    }
}
