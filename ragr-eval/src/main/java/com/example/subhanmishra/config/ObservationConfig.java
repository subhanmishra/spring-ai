package com.example.subhanmishra.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Keeps actuator requests out of tracing and out of {@code http.server.requests}.
 *
 * <p>Here the actuator shares the main port (9096) with the small review API, instead of having its own
 * management port as in ragr-app and ragr-ingest - so every Prometheus scrape was an ordinary, traced
 * request. Those traces drowned the dashboard's exemplars and none had a log line. The other two never
 * trace their scrapes, because a separate management server is not instrumented; this gives ragr-eval the
 * same behaviour without a second server.
 *
 * <p>Outside {@link EvalConfig}, which {@code app.eval.enabled} can switch off; scrapes continue anyway.
 */
@Configuration
public class ObservationConfig {

    @Bean
    public ObservationPredicate noActuatorObservations(
            @Value("${management.endpoints.web.base-path:/actuator}") String actuatorBasePath) {
        return (name, context) -> !(context instanceof ServerRequestObservationContext request
                && request.getCarrier().getRequestURI().startsWith(actuatorBasePath));
    }
}
