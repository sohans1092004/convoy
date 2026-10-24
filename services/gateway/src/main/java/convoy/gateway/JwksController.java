package convoy.gateway;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** E150: public keys for internal JWTs (services cache 5 min, refetch on unknown kid). */
@RestController
public class JwksController {
    private final GatewayKeys keys;

    public JwksController(GatewayKeys keys) { this.keys = keys; }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() { return keys.jwks(); }
}
