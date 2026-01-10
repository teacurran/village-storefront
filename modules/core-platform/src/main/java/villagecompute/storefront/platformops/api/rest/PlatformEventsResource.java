package villagecompute.storefront.platformops.api.rest;

import java.time.Duration;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestStreamElementType;

import villagecompute.storefront.platformops.api.types.HealthMetricsSummary;
import villagecompute.storefront.platformops.data.models.PlatformAdminRole;
import villagecompute.storefront.platformops.security.PlatformAdminAuthorizationService;
import villagecompute.storefront.platformops.services.HealthMetricsService;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Multi;

/**
 * Platform events SSE (Server-Sent Events) resource.
 *
 * <p>
 * Provides real-time event streaming for platform admin console dashboards via Server-Sent Events. Streams health
 * metric updates, impersonation events, and system alerts.
 *
 * <p>
 * Endpoints:
 * <ul>
 * <li>GET /api/v1/platform/events - SSE stream of platform events</li>
 * </ul>
 *
 * <p>
 * <strong>SSE Protocol:</strong> Clients connect once and receive a continuous stream of JSON events. Events are sent
 * every 5 seconds with health metrics updates. The connection includes heartbeat events every 30 seconds to detect dead
 * connections.
 *
 * <p>
 * <strong>Event Types:</strong>
 * <ul>
 * <li>{@code health_update} - System health metrics summary (every 5 seconds)</li>
 * <li>{@code heartbeat} - Keep-alive ping (every 30 seconds)</li>
 * </ul>
 *
 * <p>
 * RBAC: Requires 'platform_admin' role + 'view_health' permission.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T3: Platform admin console (SSE notifications)</li>
 * <li>Architecture: 04_Operational_Architecture.md Section 3.7</li>
 * </ul>
 */
@Path("/api/v1/platform/events")
@Authenticated
public class PlatformEventsResource {

    private static final Logger LOG = Logger.getLogger(PlatformEventsResource.class);

    @Inject
    HealthMetricsService healthMetricsService;

    @Inject
    PlatformAdminAuthorizationService authorizationService;

    @Inject
    SecurityIdentity securityIdentity;

    /**
     * Stream platform events via Server-Sent Events.
     *
     * <p>
     * Establishes an SSE connection that streams JSON-formatted events. Health metrics are sent every 5 seconds.
     * Heartbeat events are sent every 30 seconds to keep the connection alive.
     *
     * <p>
     * Clients should reconnect automatically if the connection drops. The browser EventSource API handles reconnection
     * with exponential backoff by default.
     *
     * @return Multi stream of platform events
     */
    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<PlatformEvent> streamEvents() {
        authorizationService.requirePermissions(securityIdentity, PlatformAdminRole.PERMISSION_VIEW_HEALTH);

        LOG.infof("GET /platform/events - SSE connection established for user: %s",
                securityIdentity.getPrincipal().getName());

        // Emit health metrics every 5 seconds
        Multi<PlatformEvent> healthUpdates = Multi.createFrom().ticks().every(Duration.ofSeconds(5)).map(tick -> {
            HealthMetricsSummary health = healthMetricsService.getCurrentHealth();
            return new HealthUpdateEvent(health);
        });

        // Emit heartbeat every 30 seconds
        Multi<PlatformEvent> heartbeats = Multi.createFrom().ticks().every(Duration.ofSeconds(30))
                .map(tick -> new HeartbeatEvent());

        // Merge both streams
        return Multi.createBy().merging().streams(healthUpdates, heartbeats).onCancellation().invoke(
                () -> LOG.infof("SSE connection closed for user: %s", securityIdentity.getPrincipal().getName()));
    }

    // --- Event Types ---

    /**
     * Base interface for platform events.
     */
    public sealed interface PlatformEvent permits HealthUpdateEvent, HeartbeatEvent {
        String eventType();
    }

    /**
     * Health metrics update event.
     *
     * @param health
     *            current health metrics summary
     */
    public record HealthUpdateEvent(HealthMetricsSummary health) implements PlatformEvent {
        @Override
        public String eventType() {
            return "health_update";
        }
    }

    /**
     * Heartbeat keep-alive event.
     */
    public record HeartbeatEvent() implements PlatformEvent {
        @Override
        public String eventType() {
            return "heartbeat";
        }
    }
}
