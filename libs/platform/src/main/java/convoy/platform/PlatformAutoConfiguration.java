package convoy.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.platform.idempotency.IdempotencyFilter;
import convoy.platform.idempotency.IdempotencyInterceptor;
import convoy.platform.idempotency.IdempotencyStore;
import convoy.platform.messaging.InboxConsumer;
import convoy.platform.messaging.Outbox;
import convoy.platform.messaging.OutboxRelay;
import convoy.platform.messaging.Subscriptions;
import convoy.platform.messaging.Topology;
import convoy.platform.problem.ProblemHandler;
import convoy.platform.security.InternalJwtFilter;
import convoy.platform.security.JwtVerifier;
import convoy.platform.security.ServiceTokens;
import convoy.platform.web.CorrelationFilter;
import convoy.platform.web.DbClock;
import convoy.platform.web.ServerTimeFilter;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClientBuilder;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/** Wires the platform into every service that has libs/platform on its classpath. */
@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(ConvoyProperties.class)
@Import(ProblemHandler.class)
public class PlatformAutoConfiguration implements WebMvcConfigurer {
    private final ObjectProvider<IdempotencyStore> idempotencyStore;

    public PlatformAutoConfiguration(ObjectProvider<IdempotencyStore> idempotencyStore) {
        this.idempotencyStore = idempotencyStore;
    }

    /** JSON bodies are snake_case on the wire (api-outline.md), whatever the Java field names. */
    @Bean
    org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer convoyJson() {
        return b -> b.propertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
                .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** OpenAPI schemas use the same snake_case names as the JSON on the wire. */
    @Bean
    io.swagger.v3.core.jackson.ModelResolver snakeCaseModelResolver() {
        return new io.swagger.v3.core.jackson.ModelResolver(new ObjectMapper()
                .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE));
    }

    /**
     * Platform migrations (db/platform, V0_x) and each service's own (db/migration, V1+) are independent tracks; a new platform
     * migration sorts below the service's applied versions, so it must be allowed to apply out of order.
     */
    @Bean
    org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer platformMigrationsOutOfOrder() {
        return c -> c.outOfOrder(true);
    }

    /** Every subscription must be listed in event-catalogue.yaml (checked at startup, messaging on or off). */
    @Bean
    org.springframework.beans.factory.SmartInitializingSingleton subscriptionsMatchCatalogue(ConvoyProperties p, ObjectProvider<Subscriptions> subs) {
        return () -> subs.ifAvailable(s -> s.all().forEach(x -> Subscriptions.Kind.of(p.serviceName(), x.aggregate())));
    }

    /** Required / nullable markers from record components (see RecordSchemas). */
    @Bean convoy.platform.web.RecordSchemas recordSchemas() { return new convoy.platform.web.RecordSchemas(); }

    @Bean DbClock dbClock(JdbcTemplate jdbc) { return new DbClock(jdbc); }

    @Bean IdempotencyStore idempotencyStore(JdbcTemplate jdbc) { return new IdempotencyStore(jdbc); }

    /** @Idempotent handlers run in one transaction with their idempotency record (IdempotentHandlerAdapter). */
    @Bean
    static org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations idempotentHandlers(ObjectProvider<IdempotencyStore> store,
            ObjectProvider<TransactionTemplate> tx, ObjectProvider<ObjectMapper> json) {
        return new org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations() {
            @Override
            public org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter getRequestMappingHandlerAdapter() {
                return new convoy.platform.idempotency.IdempotentHandlerAdapter(store, tx, json);
            }
        };
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Authorisation before binding and validation: a caller without the scope learns nothing about the request shape.
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            @Override
            public boolean preHandle(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res, Object handler) {
                if (handler instanceof org.springframework.web.method.HandlerMethod hm) {
                    convoy.platform.security.RequiresScope s = hm.getMethodAnnotation(convoy.platform.security.RequiresScope.class);
                    if (s != null) convoy.platform.security.Caller.current().requireScope(s.value());
                    convoy.platform.security.RequiresRole r = hm.getMethodAnnotation(convoy.platform.security.RequiresRole.class);
                    if (r != null) convoy.platform.security.Caller.current().require(r.value());
                }
                return true;
            }
        });
        registry.addInterceptor(new IdempotencyInterceptor(idempotencyStore.getObject()));
    }

    @Bean
    FilterRegistrationBean<CorrelationFilter> correlationFilter() {
        FilterRegistrationBean<CorrelationFilter> r = new FilterRegistrationBean<>(new CorrelationFilter());
        r.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return r;
    }

    @Bean
    FilterRegistrationBean<ServerTimeFilter> serverTimeFilter(DbClock clock) {
        FilterRegistrationBean<ServerTimeFilter> r = new FilterRegistrationBean<>(new ServerTimeFilter(clock));
        r.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return r;
    }

    @Bean
    @ConditionalOnMissingBean
    JwtVerifier jwtVerifier(ConvoyProperties p) { return new JwtVerifier(p.serviceName(), p.issuers()); }

    @Bean
    FilterRegistrationBean<InternalJwtFilter> internalJwtFilter(JwtVerifier v, ConvoyProperties p, ObjectMapper json) {
        FilterRegistrationBean<InternalJwtFilter> r = new FilterRegistrationBean<>(new InternalJwtFilter(v, p.publicPaths(), json));
        r.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        return r;
    }

    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilter() {
        FilterRegistrationBean<IdempotencyFilter> r = new FilterRegistrationBean<>(new IdempotencyFilter());
        r.setOrder(Ordered.HIGHEST_PRECEDENCE + 40);
        return r;
    }

    @Bean
    ServiceTokens serviceTokens(ConvoyProperties p, RestClient.Builder builder) { return new ServiceTokens(p, builder); }

    @Bean
    RestClient.Builder restClientBuilder() {
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(2));
        f.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().requestFactory(f);
    }

    @Bean
    TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(4);
        s.setThreadNamePrefix("sched-");
        return s;
    }

    @Bean Outbox outbox(JdbcTemplate jdbc, ObjectMapper json) { return new Outbox(jdbc, json); }

    // ---------------------------------------------------------------- broker (off in tests without LocalStack)

    @Bean
    @ConditionalOnProperty(prefix = "convoy.messaging", name = "enabled", havingValue = "true", matchIfMissing = true)
    SnsClient snsClient(ConvoyProperties p) {
        SnsClientBuilder b = SnsClient.builder().region(Region.of(p.aws().region()));
        if (p.aws().endpoint() != null) {
            b.endpointOverride(URI.create(p.aws().endpoint())).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
        } else {
            b.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        return b.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "convoy.messaging", name = "enabled", havingValue = "true", matchIfMissing = true)
    SqsClient sqsClient(ConvoyProperties p) {
        SqsClientBuilder b = SqsClient.builder().region(Region.of(p.aws().region()));
        if (p.aws().endpoint() != null) {
            b.endpointOverride(URI.create(p.aws().endpoint())).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
        } else {
            b.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        return b.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "convoy.messaging", name = "enabled", havingValue = "true", matchIfMissing = true)
    Topology topology(SnsClient sns, SqsClient sqs, ObjectMapper json, ConvoyProperties p) {
        return new Topology(sns, sqs, json, p.messaging().visibilityTimeoutSeconds());
    }

    /**
     * Consumer and DLQ metrics (messaging-failure-tests.md): inbox_duplicates_total, stale_events_total, parked_events_total,
     * snapshot_fallbacks_total per consumer; dlq_depth per DLQ, checked every 30 s with an ALARM DLQ_NOT_EMPTY log line
     * (a CloudWatch alarm on ApproximateNumberOfMessagesVisible in AWS).
     */
    @Bean
    @ConditionalOnProperty(prefix = "convoy.messaging", name = "enabled", havingValue = "true", matchIfMissing = true)
    convoy.platform.messaging.MessagingMetrics messagingMetrics(ObjectProvider<InboxConsumer> consumer, Topology topology,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters, TaskScheduler scheduler) {
        return new convoy.platform.messaging.MessagingMetrics(consumer.getIfAvailable(), topology, meters.getIfAvailable(), scheduler);
    }

    @Bean
    @ConditionalOnProperty(prefix = "convoy.messaging", name = "enabled", havingValue = "true", matchIfMissing = true)
    OutboxRelay outboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, SnsClient sns, Topology topology, ObjectMapper json,
                            ConvoyProperties p, TaskScheduler scheduler) {
        OutboxRelay relay = new OutboxRelay(jdbc, tx, sns, topology, json, p.serviceName());
        scheduler.scheduleWithFixedDelay(relay::tick, Duration.ofMillis(p.messaging().relayIntervalMs()));
        return relay;
    }

    @Bean
    @ConditionalOnProperty(prefix = "convoy.messaging", name = "enabled", havingValue = "true", matchIfMissing = true)
    InboxConsumer inboxConsumer(ConvoyProperties p, SqsClient sqs, Topology topology, JdbcTemplate jdbc, TransactionTemplate tx,
                                ObjectMapper json, ObjectProvider<Subscriptions> subscriptions) {
        return new InboxConsumer(p.serviceName(), sqs, topology, jdbc, tx, json,
                subscriptions.getIfAvailable(Subscriptions::new), p.messaging().waitSeconds(), p.messaging().gapSnapshotAfter());
    }
}
