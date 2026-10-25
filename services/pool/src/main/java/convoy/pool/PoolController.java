package convoy.pool;

import convoy.platform.idempotency.Idempotent;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** E032-E039 (P1) and E042 (P2, not built). Aggregate reads carry ETag = version; If-Match is the membership version. */
@RestController
public class PoolController {
    private final Pools pools;

    public PoolController(Pools pools) { this.pools = pools; }

    public record CreateBody(@NotBlank String modelSlug, @NotBlank String city, @NotBlank String purchaseMonth) {}

    @PostMapping("/pools")
    @Idempotent
    public ResponseEntity<Api.PoolCreated> create(@Valid @RequestBody CreateBody b) {
        Map<String, Object> r = pools.createOrFind(b.modelSlug(), b.city(), b.purchaseMonth());
        return ResponseEntity.status(Boolean.TRUE.equals(r.get("created")) ? 201 : 200).body(convoy.platform.web.Dto.as(r, Api.PoolCreated.class));
    }

    @GetMapping("/pools/{id}")
    public ResponseEntity<Api.Pool> get(@PathVariable UUID id) {
        Caller c = Caller.current().require("buyer", "dealer_staff", "dealer_admin", "platform_admin");
        Map<String, Object> s = pools.snapshot(id, c);
        return ResponseEntity.ok().eTag("\"" + s.get("version") + "\"").body(convoy.platform.web.Dto.as(s, Api.Pool.class));
    }

    @PostMapping("/pools/{id}/members")
    @Idempotent
    public ResponseEntity<Api.Membership> join(@PathVariable UUID id) {
        return ResponseEntity.status(201).body(convoy.platform.web.Dto.as(pools.join(id), Api.Membership.class));
    }

    public record CommitBody(@NotBlank String termsVersion) {}

    @PostMapping("/pools/{id}/commitment")
    @Idempotent
    public Api.Commitment commit(@PathVariable UUID id, @Valid @RequestBody CommitBody b) {
        return convoy.platform.web.Dto.as(pools.commit(id, b.termsVersion()), Api.Commitment.class);
    }

    @DeleteMapping("/pools/{id}/members/me")
    @Idempotent
    public Api.Membership leave(@PathVariable UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return convoy.platform.web.Dto.as(pools.leave(id, version(ifMatch)), Api.Membership.class);
    }

    public record AcceptBody(@NotNull UUID quoteId, @NotNull UUID variantId) {}

    @PutMapping("/pools/{id}/members/me/acceptance")
    @Idempotent
    public Api.Membership accept(@PathVariable UUID id, @Valid @RequestBody AcceptBody b, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return convoy.platform.web.Dto.as(pools.accept(id, b.quoteId(), b.variantId(), version(ifMatch)), Api.Membership.class);
    }

    @DeleteMapping("/pools/{id}/members/me/acceptance")
    @Idempotent
    public Api.Membership withdraw(@PathVariable UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return convoy.platform.web.Dto.as(pools.withdrawAcceptance(id, version(ifMatch)), Api.Membership.class);
    }

    @GetMapping("/pools/{id}/members/me")
    public ResponseEntity<Api.Membership> mine(@PathVariable UUID id) {
        Caller c = Pools.buyer();
        Map<String, Object> m = pools.membership(id, c.userId(), false).map(Pools::view)
                .orElseThrow(() -> ApiProblem.of(ProblemType.NOT_FOUND, "You are not in this pool."));
        return ResponseEntity.ok().eTag("\"" + m.get("version") + "\"").body(convoy.platform.web.Dto.as(m, Api.Membership.class));
    }

    // ------------------------------------------------------------------ internal, for realtime (scope pools:access)

    /** May this buyer / dealership watch the pool's stream? Members (not LEFT) and staff of invited dealerships (E120 authz). */
    @GetMapping("/internal/pools/{id}/access")
    @convoy.platform.security.RequiresScope("pools:access")
    public Api.StreamAccess access(@PathVariable UUID id, @RequestParam(name = "buyer_id", required = false) UUID buyer,
                                   @RequestParam(name = "dealership_id", required = false) UUID dealership) {
        return new Api.StreamAccess(pools.mayWatch(id, buyer, dealership));
    }

    /** The pool's current aggregate version, for realtime's gap snapshot fallback (it never needs the content). */
    @GetMapping("/internal/pools/{id}/version")
    @convoy.platform.security.RequiresScope("pools:access")
    public Api.PoolVersion version(@PathVariable UUID id) { return new Api.PoolVersion(pools.version(id)); }

    // ------------------------------------------------------------------ preferences and matching (E030, E031)

    @GetMapping("/me/preferences")
    public Api.Preferences preferences() { return pools.preferences(Pools.buyer().userId()); }

    @PutMapping("/me/preferences")
    @convoy.platform.security.RequiresRole("buyer")
    public Api.Preferences savePreferences(@Valid @RequestBody Api.Preferences b) { return pools.savePreferences(Pools.buyer().userId(), b); }

    /** Suggestions for the buyer's saved cars; the web passes the saved model slugs (catalog E024) as repeated ?model=. */
    @GetMapping("/me/pool-suggestions")
    public Api.Suggestions suggestions(@RequestParam(name = "model", required = false) java.util.List<String> models) {
        return pools.suggestions(Pools.buyer().userId(), models == null ? java.util.List.of() : models);
    }

    @PutMapping("/pools/{id}/members/me/reround-choice")
    @convoy.platform.security.RequiresRole("buyer")
    public void reroundChoice(@PathVariable UUID id) {
        throw ApiProblem.of(ProblemType.NOT_IMPLEMENTED_IN_P1, "The re-round choice (E042, AMENDMENT-002) is specified but not built in Phase 1.");
    }

    static long version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) throw ApiProblem.of(ProblemType.PRECONDITION_REQUIRED, "Send If-Match with the membership version.");
        try {
            return Long.parseLong(ifMatch.replace("\"", "").replace("W/", "").trim());
        } catch (NumberFormatException e) {
            throw ApiProblem.of(ProblemType.PRECONDITION_REQUIRED, "If-Match must be the membership version.");
        }
    }
}
