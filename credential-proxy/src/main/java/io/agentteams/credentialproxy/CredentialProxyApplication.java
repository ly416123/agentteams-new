package io.agentteams.credentialproxy;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootApplication
public class CredentialProxyApplication {
    public static void main(String[] args) {
        SpringApplication.run(CredentialProxyApplication.class, args);
    }

    @Bean
    Clock credentialProxyClock() {
        return Clock.systemUTC();
    }

    @Bean
    ModelProxyAuthenticator modelProxyAuthenticator(JdbcTemplate jdbc) {
        return new JdbcModelProxyCredentialAuthenticator(jdbc);
    }

    @Bean
    ActiveAttemptRepository activeAttemptRepository(JdbcTemplate jdbc) {
        return new JdbcActiveAttemptRepository(jdbc);
    }

    @Bean
    AttemptCredentialIssuer attemptCredentialIssuer(
            JdbcTemplate jdbc,
            @Value("${agentteams.credential-proxy.attempt-token-ttl-seconds:120}") int ttlSeconds) {
        return new JdbcAttemptCredentialIssuer(jdbc, ttlSeconds);
    }

    @Bean
    AttemptExecutionContextResolver attemptExecutionContextResolver(
            ActiveAttemptRepository attempts, Clock credentialProxyClock) {
        return new AttemptExecutionContextResolver(attempts, credentialProxyClock);
    }

    @Bean
    CredentialSource credentialSource(@Value("${agentteams.credential-proxy.secret-root:/run/secrets}") String root) {
        return new FileCredentialSource(Path.of(root));
    }

    @Bean
    HttpClient modelProxyHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean(destroyMethod = "shutdown")
    ScheduledExecutorService modelProxyStreamMonitor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(8, task -> {
            Thread thread = new Thread(task, "model-proxy-lease-monitor");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    @Bean
    Semaphore modelProxyActiveStreamPermits(
            @Value("${agentteams.credential-proxy.max-active-streams:64}") int maxActiveStreams) {
        if (maxActiveStreams < 1) throw new IllegalArgumentException("max active streams must be positive");
        return new Semaphore(maxActiveStreams, true);
    }

    @Bean
    ModelProxyService modelProxyService(
            ModelProxyAuthenticator authenticator,
            AttemptExecutionContextResolver contexts,
            CredentialSource credentials,
            HttpClient modelProxyHttpClient,
            Clock credentialProxyClock,
            ScheduledExecutorService modelProxyStreamMonitor,
            Semaphore modelProxyActiveStreamPermits,
            @Value("${agentteams.credential-proxy.upstream-responses-url:https://api.openai.com/v1/responses}") String endpoint,
            @Value("${agentteams.credential-proxy.allowed-models:gpt-5.6-luna}") String allowedModels) {
        Set<String> models = Arrays.stream(allowedModels.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
        return new ModelProxyService(authenticator, contexts, credentials, java.net.URI.create(endpoint),
                models, modelProxyHttpClient, credentialProxyClock, modelProxyStreamMonitor,
                modelProxyActiveStreamPermits);
    }
}
