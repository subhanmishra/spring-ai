package com.example.subhanmishra.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Keeps actuator requests out of tracing and out of {@code http.server.requests}.
 *
 * <p>This application has no API, so actuator shares the main port (9096) rather than getting a
 * management port of its own as ragr-app and ragr-ingest do - and there, a request to it is an ordinary
 * server request. Every Prometheus scrape, one every 15 seconds, was therefore traced, exported to Tempo
 * and recorded with an exemplar: 234 of the roughly 285 exemplars on the overview dashboard's HTTP
 * panels in one measured hour. None of those traces has a log line, so almost every dot there led to a
 * "Related logs" query that returned nothing. The other two applications never observe their scrapes
 * because a separate management server is not instrumented; this predicate gives ragr-eval the same
 * behaviour without running a second server for it.
 *
 * <p>Deliberately outside {@link EvalConfig}, which is switched off with {@code app.eval.enabled} - the
 * scrapes continue either way.
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
