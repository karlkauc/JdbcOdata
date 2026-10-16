package at.jdbcodata.query;

public record QueryParameter(
        String name,
        ParameterType type,
        boolean required,
        String defaultValue,
        String description,
        boolean sensitive) {
}
