package lib.kasuga.rendering.models.uml.dynamic.proxy;

import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RuntimeController;

/**
 * Adapter between one observed input and one scope controller. The controller advances exactly
 * once; this port may only prepare input before that advance and expose the committed output
 * afterwards.
 */
public interface ProxyControllerPort<I, O> {

    void prepare(I input);

    RuntimeController<Void> controller();

    O output();

    static <I, O> ProxyControllerPort<I, O> none() {
        return new ProxyControllerPort<>() {
            @Override
            public void prepare(I input) {
            }

            @Override
            public RuntimeController<Void> controller() {
                return RuntimeController.none();
            }

            @Override
            public O output() {
                return null;
            }
        };
    }
}
