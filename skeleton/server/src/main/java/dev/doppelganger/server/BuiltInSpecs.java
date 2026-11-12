package dev.doppelganger.server;

import dev.doppelganger.core.DomainSpec;
import dev.doppelganger.core.Invariant;
import dev.doppelganger.core.MergePolicy;
import dev.doppelganger.core.RecordSpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * TEMPORARY (Phase 1): the two demo record types written in code so the server can run.
 * Phase 2 replaces this with the YAML domain spec loader reading visit-ledger/ and stock-ledger/;
 * app-specific rules must not stay in the skeleton.
 */
@Configuration
public class BuiltInSpecs {

    public static RecordSpec patient() {
        return new RecordSpec("patient", Map.of(
                "name", MergePolicy.LATEST,
                "visitNotes", MergePolicy.APPEND_ONLY,
                "symptoms", MergePolicy.SET_UNION,
                "medicineDose", MergePolicy.CRITICAL,
                "followUp", MergePolicy.AI_JUDGE,
                "age", MergePolicy.LATEST,
                "village", MergePolicy.LATEST), List.of());
    }

    public static RecordSpec item() {
        return new RecordSpec("item", Map.of(
                "name", MergePolicy.LATEST,
                "quantity", MergePolicy.COUNTER,
                "price", MergePolicy.CRITICAL,
                "supplierNotes", MergePolicy.AI_JUDGE,
                "unitSize", MergePolicy.LATEST),
                List.of(new Invariant("quantity", Invariant.Comparison.GE, 0)));
    }

    @Bean
    public DomainSpec domainSpec() {
        return new DomainSpec(patient(), item());
    }
}
