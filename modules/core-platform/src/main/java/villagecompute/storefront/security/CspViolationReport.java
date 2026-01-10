package villagecompute.storefront.security;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * CSP violation report structure (JSON payload from browser).
 *
 * <p>
 * Browsers send violation reports to the report-uri endpoint when CSP policy is violated.
 *
 * <p>
 * <strong>Example Report:</strong>
 *
 * <pre>
 * {
 *   "csp-report": {
 *     "document-uri": "https://example.com/page",
 *     "violated-directive": "script-src 'self'",
 *     "blocked-uri": "https://evil.com/malicious.js",
 *     "source-file": "https://example.com/page",
 *     "line-number": 42,
 *     "column-number": 10
 *   }
 * }
 * </pre>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I6.T2: CSP violation reporting endpoint</li>
 * <li>W3C CSP Spec: https://www.w3.org/TR/CSP3/#violation-reports</li>
 * </ul>
 */
public class CspViolationReport {

    @JsonProperty("csp-report")
    private CspReport cspReport;

    public CspReport getCspReport() {
        return cspReport;
    }

    public void setCspReport(CspReport cspReport) {
        this.cspReport = cspReport;
    }

    public static class CspReport {

        @JsonProperty("document-uri")
        private String documentUri;

        @JsonProperty("violated-directive")
        private String violatedDirective;

        @JsonProperty("effective-directive")
        private String effectiveDirective;

        @JsonProperty("original-policy")
        private String originalPolicy;

        @JsonProperty("blocked-uri")
        private String blockedUri;

        @JsonProperty("referrer")
        private String referrer;

        @JsonProperty("status-code")
        private Integer statusCode;

        @JsonProperty("source-file")
        private String sourceFile;

        @JsonProperty("line-number")
        private Integer lineNumber;

        @JsonProperty("column-number")
        private Integer columnNumber;

        // Getters and setters

        public String getDocumentUri() {
            return documentUri;
        }

        public void setDocumentUri(String documentUri) {
            this.documentUri = documentUri;
        }

        public String getViolatedDirective() {
            return violatedDirective;
        }

        public void setViolatedDirective(String violatedDirective) {
            this.violatedDirective = violatedDirective;
        }

        public String getEffectiveDirective() {
            return effectiveDirective;
        }

        public void setEffectiveDirective(String effectiveDirective) {
            this.effectiveDirective = effectiveDirective;
        }

        public String getOriginalPolicy() {
            return originalPolicy;
        }

        public void setOriginalPolicy(String originalPolicy) {
            this.originalPolicy = originalPolicy;
        }

        public String getBlockedUri() {
            return blockedUri;
        }

        public void setBlockedUri(String blockedUri) {
            this.blockedUri = blockedUri;
        }

        public String getReferrer() {
            return referrer;
        }

        public void setReferrer(String referrer) {
            this.referrer = referrer;
        }

        public Integer getStatusCode() {
            return statusCode;
        }

        public void setStatusCode(Integer statusCode) {
            this.statusCode = statusCode;
        }

        public String getSourceFile() {
            return sourceFile;
        }

        public void setSourceFile(String sourceFile) {
            this.sourceFile = sourceFile;
        }

        public Integer getLineNumber() {
            return lineNumber;
        }

        public void setLineNumber(Integer lineNumber) {
            this.lineNumber = lineNumber;
        }

        public Integer getColumnNumber() {
            return columnNumber;
        }

        public void setColumnNumber(Integer columnNumber) {
            this.columnNumber = columnNumber;
        }
    }
}
