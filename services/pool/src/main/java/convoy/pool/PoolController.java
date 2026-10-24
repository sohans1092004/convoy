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
import org.springframework.web.bind.annotation.RestController;

/** E032-E039 (P1) and E042 (P2, not built). Aggregate reads carry ETag = version; If-Match is the membership version. */
@RestController
public class PoolController {
    private final Pools pools;

    public PoolController(Pools pools) { this.pools = pools; }

    public record CreateBody(@NotBlank String modelSlug, @NotBlank String city, @NotBlank String purchaseMonth) {}

    @PostMapping("/pools")
    @Idempotent
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateBody b) {
        Map<String, Object> r = pools.createOrFind(b.modelSlug(), b.city(), b.purchaseMonth());
        return ResponseEntity.status(Boolean.TRUE.equals(r.get("created")) ? 201 : 200).body(r);
    }

    @GetMapping("/pools/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable UUID id) {
        Caller c = Caller.current().require("buyer", "dealer_staff", "dealer_admin", "platform_admin");
        Map<String, Object> s = pools.snapshot(id, c);
        return ResponseEntity.ok().eTag("\"" + s.get("version") + "\"").body(s);
    }

    @PostMapping("/pools/{id}/members")
    @Idempotent
    public ResponseEntity<Map<String, Object>> join(@PathVariable UUID id) {
        return ResponseEntity.status(201).body(pools.join(id));
    }

    public record CommitBody(@NotBlank String termsVersion) {}

    @PostMapping("/pools/{id}/commitment")
    @Idempotent
    public Map<String, Object> commit(@PathVariable UUID id, @Valid @RequestBody CommitBody b) {
        return pools.commit(id, b.termsVersion());
    }

    @DeleteMapping("/pools/{id}/members/me")
    @Idempotent
    public Map<String, Object> leave(@PathVariable UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return pools.leave(id, version(ifMatch));
    }

    public record AcceptBody(@NotNull UUID quoteId, @NotNull UUID variantId) {}

    @PutMapping("/pools/{id}/members/me/acceptance")
    @Idempotent
    public Map<String, Object> accept(@PathVariable UUID id, @Valid @RequestBody AcceptBody b, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return pools.accept(id, b.quoteId(), b.variantId(), version(ifMatch));
    }

    @DeleteMapping("/pools/{id}/members/me/acceptance")
    @Idempotent
    public Map<String, Object> withdraw(@PathVariable UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return pools.withdrawAcceptance(id, version(ifMatch));
    }

    @GetMapping("/pools/{id}/members/me")
    public ResponseEntity<Map<String, Object>> mine(@PathVariable UUID id) {
        Caller c = Pools.buyer();
        Map<String, Object> m = pools.membership(id, c.userId(), false).map(Pools::view)
                .orElseThrow(() -> ApiProblem.of(ProblemType.NOT_FOUND, "You are not in this pool."));
        return ResponseEntity.ok().eTag("\"" + m.get("version") + "\"").body(m);
    }

    @PutMapping("/pools/{id}/members/me/reround-choice")
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
