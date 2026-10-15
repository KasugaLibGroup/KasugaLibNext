package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

public class ProxyInstance<R, T extends ElementProxy<R, T>> {

    private final T proxyType;

    @Setter
    @Nullable
    @Getter
    private ModelInstance model;

    public ProxyInstance(T proxyType) {
        this(proxyType, null);
    }

    public ProxyInstance(T proxyType, ModelInstance model) {
        this.proxyType = proxyType;
        this.model = model;
    }

    public boolean isValidInput(Object input) {
        return proxyType.isValidInstance(input);
    }


}
