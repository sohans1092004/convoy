package convoy.platform.problem;

/**
 * Problem types from docs/architecture/problem-types.yaml (slug, status, code, title) plus the P1-only
 * not-implemented-in-p1. ProblemTypesTest keeps this enum identical to the YAML.
 */
public enum ProblemType {
    VALIDATION_FAILED("validation-failed", 400, "Request body or parameters are invalid"),
    UNAUTHENTICATED("unauthenticated", 401, "No valid session or token"),
    FORBIDDEN("forbidden", 403, "Authenticated but not allowed on this resource"),
    NOT_FOUND("not-found", 404, "Resource does not exist or is not visible to the caller"),
    INVALID_TRANSITION("invalid-transition", 409, "The aggregate is not in a state that allows this action"),
    VERSION_CONFLICT("version-conflict", 409, "The aggregate changed since you read it (If-Match / expected version mismatch)"),
    DEADLINE_PASSED("deadline-passed", 409, "The database clock at commit time was at or past the deadline"),
    ROUND_NOT_OPEN("round-not-open", 409, "No quote round is open for this pool yet"),
    ALREADY_MEMBER("already-member", 409, "Buyer already has a membership in this pool"),
    ACTIVE_COMMITMENT_EXISTS("active-commitment-exists", 409, "Buyer already holds an active commitment for this model"),
    NOT_A_MEMBER("not-a-member", 409, "Buyer is not a committed member of this pool"),
    CAPACITY_REACHED("capacity-reached", 409, "The quote's max_units confirmations are already taken"),
    DEPOSIT_NOT_CAPTURED("deposit-not-captured", 409, "Deposit has no captured payment yet"),
    QUOTE_NOT_FINAL("quote-not-final", 409, "Only FINAL quotes can be accepted"),
    QUOTE_INVALID("quote-invalid", 422, "Quote fails pricing or tier validation (see errors[])"),
    INVALID_TIERS("invalid-tiers", 422, "Tier ladder is malformed (gap, overlap, non-monotonic, does not cover minimum)"),
    DISCOUNT_EXCEEDS_LIMIT("discount-exceeds-limit", 422, "Discount larger than the allowed maximum"),
    IDEMPOTENCY_KEY_REUSED("idempotency-key-reused", 422, "Idempotency-Key was already used with a different request body"),
    IDEMPOTENCY_KEY_MISSING("idempotency-key-missing", 428, "This endpoint requires an Idempotency-Key header"),
    PRECONDITION_REQUIRED("precondition-required", 428, "This endpoint requires If-Match with the aggregate version"),
    RATE_LIMITED("rate-limited", 429, "Too many requests"),
    SECOND_APPROVAL_REQUIRED("second-approval-required", 409, "This manual money action needs approval by a second admin"),
    WEBHOOK_SIGNATURE_INVALID("webhook-signature-invalid", 400, "Razorpay webhook HMAC check failed"),
    REVIEW_NOT_ELIGIBLE("review-not-eligible", 403, "Only buyers with a delivered booking from this dealer can review"),
    DEPENDENCY_UNAVAILABLE("dependency-unavailable", 503, "A required downstream dependency is unavailable; retry with the same Idempotency-Key"),
    /** P1 only: the command exists in the spec but is outside the walking skeleton. */
    NOT_IMPLEMENTED_IN_P1("not-implemented-in-p1", 501, "This command is specified but not built in Phase 1");

    public static final String BASE_URI = "https://convoy.example/problems/";

    public final String slug;
    public final int status;
    public final String title;

    ProblemType(String slug, int status, String title) {
        this.slug = slug;
        this.status = status;
        this.title = title;
    }

    public String code() { return name(); }
    public String uri() { return BASE_URI + slug; }
}
