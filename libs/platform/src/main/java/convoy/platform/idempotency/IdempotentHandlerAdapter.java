package convoy.platform.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.platform.Faults;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod;

/**
 * Runs each {@link Idempotent} handler in one transaction: idempotency row first, then the business change
 * (service methods join the transaction), then the serialised response into the same row, then commit.
 * A failed request rolls back entirely and stores nothing, so a retry runs it again.
 */
public class IdempotentHandlerAdapter extends RequestMappingHandlerAdapter {
    /** Fault point: the transaction has committed but the response was never sent. */
    public static final String CRASH_AFTER_COMMIT = "idempotency.after-commit";
    private final ObjectProvider<IdempotencyStore> store;
    private final ObjectProvider<TransactionTemplate> tx;
    private final ObjectProvider<ObjectMapper> json;

    public IdempotentHandlerAdapter(ObjectProvider<IdempotencyStore> store, ObjectProvider<TransactionTemplate> tx, ObjectProvider<ObjectMapper> json) {
        this.store = store;
        this.tx = tx;
        this.json = json;
    }

    @Override
    protected ServletInvocableHandlerMethod createInvocableHandlerMethod(HandlerMethod hm) {
        if (!hm.hasMethodAnnotation(Idempotent.class)) return super.createInvocableHandlerMethod(hm);
        return new ServletInvocableHandlerMethod(hm) {
            @Override
            protected Object doInvoke(Object... args) throws Exception {
                ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
                IdempotencyStore.Key k = (IdempotencyStore.Key) attrs.getRequest().getAttribute(IdempotencyInterceptor.KEY_ATTR);
                IdempotencyStore s = store.getObject();
                Object result;
                try {
                    result = tx.getObject().execute(status -> {
                        s.begin(k);
                        Object r = invoke(args);
                        ResponseEntity<?> e = r instanceof ResponseEntity<?> re ? re : null;
                        Object body = e != null ? e.getBody() : r;
                        s.complete(k, e != null ? e.getStatusCode().value() : defaultStatus(), "application/json", body == null ? null : bytes(body));
                        return r;
                    });
                } catch (DuplicateKeyException dup) {
                    // The same key committed concurrently (a double click): answer as its replay.
                    HttpServletResponse res = attrs.getResponse();
                    IdempotencyInterceptor.replay(k, s.find(k.key(), k.caller()).orElseThrow(), res);
                    res.flushBuffer();
                    return null;
                } catch (Checked c) {
                    throw c.cause;
                }
                Faults.hit(CRASH_AFTER_COMMIT);
                return result;
            }

            private Object invoke(Object[] args) {
                try {
                    return super.doInvoke(args);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new Checked(e);
                }
            }

            private int defaultStatus() {
                ResponseStatus rs = getMethodAnnotation(ResponseStatus.class);
                return rs == null ? 200 : rs.code().value();
            }
        };
    }

    private byte[] bytes(Object body) {
        try {
            return json.getObject().writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Carries a checked exception out of the transaction callback. */
    private static final class Checked extends RuntimeException {
        final Exception cause;

        Checked(Exception cause) {
            super(cause);
            this.cause = cause;
        }
    }
}
