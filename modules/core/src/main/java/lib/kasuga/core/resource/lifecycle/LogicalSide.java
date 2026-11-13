package lib.kasuga.core.resource.lifecycle;

public enum LogicalSide {
    CLIENT, SERVER, BOTH, NONE;
    public static LogicalSide parse(String value) { return valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
}
