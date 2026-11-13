package lib.kasuga.core.resource.lifecycle;

import java.util.concurrent.CompletionStage;

public interface ResourceEntryPoint {
    EntryPointDescriptor descriptor();
    CompletionStage<? extends PreparedConsumerBatch> prepare(ResourceBatch batch, InvocationContext context);
}
