package lib.kasuga.registration.data_driven.pipeline;

import lib.kasuga.registration.data_driven.pipeline.BuildDiagnostic.Code;
import lib.kasuga.registration.data_driven.pipeline.BuildDiagnostic.Severity;
import lib.kasuga.registration.data_driven.pipeline.BuildDiagnostic.Stage;
import lib.kasuga.registration.data_driven.source.SourceDescriptor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

final class DiagnosticCollector {
    private final List<BuildDiagnostic> diagnostics = new ArrayList<>();

    void error(
            @Nullable SourceDescriptor source,
            Stage stage,
            Code code,
            @Nullable String logicalPath,
            @Nullable String jsonPointer,
            @Nullable String handler,
            String message,
            @Nullable String suggestion
    ) {
        add(Severity.ERROR, source, stage, code, logicalPath, jsonPointer, handler, message, suggestion);
    }

    void warning(
            @Nullable SourceDescriptor source,
            Stage stage,
            Code code,
            @Nullable String logicalPath,
            @Nullable String jsonPointer,
            @Nullable String handler,
            String message,
            @Nullable String suggestion
    ) {
        add(Severity.WARNING, source, stage, code, logicalPath, jsonPointer, handler, message, suggestion);
    }

    void add(
            Severity severity,
            @Nullable SourceDescriptor source,
            Stage stage,
            Code code,
            @Nullable String logicalPath,
            @Nullable String jsonPointer,
            @Nullable String handler,
            String message,
            @Nullable String suggestion
    ) {
        diagnostics.add(new BuildDiagnostic(
                severity,
                stage,
                code,
                source == null ? null : source.sourceId(),
                source == null ? null : source.ownerId(),
                logicalPath,
                jsonPointer,
                handler,
                message,
                suggestion
        ));
    }

    List<BuildDiagnostic> snapshot() {
        return List.copyOf(diagnostics);
    }
}
