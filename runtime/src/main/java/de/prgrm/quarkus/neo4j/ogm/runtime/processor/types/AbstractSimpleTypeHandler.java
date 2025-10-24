package de.prgrm.quarkus.neo4j.ogm.runtime.processor.types;

import static de.prgrm.quarkus.neo4j.ogm.runtime.processor.util.MapperUtil.*;

import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import com.palantir.javapoet.CodeBlock;

import de.prgrm.quarkus.neo4j.ogm.runtime.processor.TypeHandler;

public abstract class AbstractSimpleTypeHandler implements TypeHandler {

    protected abstract String getSupportedType();

    protected abstract String readMethod();

    public boolean supports(VariableElement field, Types types, Elements elements) {
        var fieldErased = types.erasure(field.asType());

        var supportedElement = elements.getTypeElement(getSupportedType());
        if (supportedElement == null) {
            return false;
        }
        var supportedType = supportedElement.asType();

        return switch (fieldErased.getKind()) {
            case BOOLEAN -> "java.lang.Boolean".equals(getSupportedType());
            case INT -> "java.lang.Integer".equals(getSupportedType());
            case LONG -> "java.lang.Long".equals(getSupportedType());
            case FLOAT -> "java.lang.Float".equals(getSupportedType());
            case DOUBLE -> "java.lang.Double".equals(getSupportedType());
            case CHAR -> "java.lang.Character".equals(getSupportedType());
            case BYTE -> "java.lang.Byte".equals(getSupportedType());
            case SHORT -> "java.lang.Short".equals(getSupportedType());
            default ->
                types.isSameType(fieldErased, supportedType);
        };
    }

    @Override
    public CodeBlock generateSetterCode(VariableElement field, String targetVar, String valueSource) {
        return CodeBlock.of("$L.$L($L.get($S).$L());\n",
                targetVar,
                resolveSetterName(field),
                valueSource,
                getPropertyName(field),
                readMethod());
    }

    @Override
    public CodeBlock generateToDbCode(VariableElement field, String entityVar, String mapVar) {
        return CodeBlock.of("$L.put($S, $L.$L());\n",
                mapVar,
                getPropertyName(field),
                entityVar,
                resolveGetterName(field));
    }
}
