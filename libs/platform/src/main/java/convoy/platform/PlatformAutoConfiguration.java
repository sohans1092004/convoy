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

    @Bean DbClock dbClock(JdbcTemplate jdbc) { return new DbClock(jdbc); }

    @Bean IdempotencyStore idempotencyStore(JdbcTemplate jdbc) { return new IdempotencyStore(jdbc); }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
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
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(IdempotencyStore store) {
        FilterRegistrationBean<IdempotencyFilter> r = new FilterRegistrationBean<>(new IdempotencyFilter(store));
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
    Topology topology(SnsClient sns, SqsClient sqs, ObjectMapper json) { return new Topology(sns, sqs, json); }

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
                subscriptions.getIfAvailable(Subscriptions::new), p.messaging().waitSeconds());
    }
}
