package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.orchestrator;

import java.util.Map;

/** Extension point for independently owned native migration stages. */
@FunctionalInterface
public interface NativeMigrationStage {
    StageResult generate(MigrationContext context) throws Exception;

    record StageResult(String name, Map<String, Integer> metrics) {
        public StageResult { metrics = Map.copyOf(metrics); }
        public static StageResult of(String name, Map<String, Integer> metrics) {
            return new StageResult(name, metrics);
        }
    }
}
