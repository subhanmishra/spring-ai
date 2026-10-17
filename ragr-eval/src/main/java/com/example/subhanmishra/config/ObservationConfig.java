package com.example.subhanmishra.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Keeps actuator requests out of tracing and out of {@code http.server.requests}.
 *
 * <p>Here the actuator shares the main port (9096) with the review API, so without this every Prometheus
 * scrape would be a traced request, drowning the dashboard's exemplars. ragr-app and ragr-ingest get the
 * same behaviour from their separate, uninstrumented management port.
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
