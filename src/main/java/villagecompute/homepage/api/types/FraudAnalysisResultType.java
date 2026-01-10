package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * Response type for AI fraud detection analysis results.
 *
 * <p>
 * Contains fraud probability score, confidence level, and list of detected fraud indicators. Used by
 * FraudDetectionService to return structured analysis results.
 */
public record FraudAnalysisResultType(@JsonProperty("is_suspicious") boolean isSuspicious,

        @JsonProperty("confidence") BigDecimal confidence,

        @JsonProperty("reasons") List<String> reasons,

        @JsonProperty("prompt_version") String promptVersion) {
    /**
     * Creates a non-suspicious result.
     *
     * @return analysis result indicating no fraud detected
     */
    public static FraudAnalysisResultType clean(String promptVersion) {
        return new FraudAnalysisResultType(false, BigDecimal.ZERO, List.of(), promptVersion);
    }

    /**
     * Creates a suspicious result with specific reasons.
     *
     * @param confidence
     *            fraud probability (0.00 to 1.00)
     * @param reasons
     *            list of detected fraud indicators
     * @param promptVersion
     *            AI prompt version used for analysis
     * @return analysis result indicating fraud detected
     */
    public static FraudAnalysisResultType suspicious(BigDecimal confidence, List<String> reasons,
            String promptVersion) {
        return new FraudAnalysisResultType(true, confidence, reasons, promptVersion);
    }

    /**
     * Computes fraud score for storage in database.
     *
     * <p>
     * Returns confidence if suspicious, otherwise 0.00.
     *
     * @return fraud score between 0.00 and 1.00
     */
    public BigDecimal fraudScore() {
        return isSuspicious ? confidence : BigDecimal.ZERO;
    }
}
