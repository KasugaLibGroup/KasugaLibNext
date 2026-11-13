package lib.kasuga.core.resource.lifecycle;

public enum ResourceEvent {
    HOST_BOOTSTRAP, CLIENT_STARTUP, SERVER_STARTUP, RESOURCE_RELOAD, REMOTE_CONNECT;
    public static ResourceEvent parse(String value) { return valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
}
