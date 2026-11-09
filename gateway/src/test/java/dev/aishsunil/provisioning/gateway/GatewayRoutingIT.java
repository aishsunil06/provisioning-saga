package dev.aishsunil.provisioning.gateway;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Routing, against a real downstream rather than a mock.
 *
 * <p>The stub is the JDK's own HTTP server. The gateway proxies over the
 * network, so a mock that is not listening on a socket would not exercise the
 * thing under test, and a routing test that passes without anything being
 * routed is worse than no test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingIT {

    private static HttpServer downstream;

    private static final List<String> receivedPaths = Collections.synchronizedList(new java.util.ArrayList<>());

    private static final List<String> receivedRequestIds = Collections.synchronizedList(new java.util.ArrayList<>());

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void startDownstream() throws IOException {
        downstream = HttpServer.create(new InetSocketAddress(0), 0);
        downstream.createContext("/", (exchange) -> {
            receivedPaths.add(exchange.getRequestURI().getPath());
            receivedRequestIds.add(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        downstream.start();
    }

    @AfterAll
    static void stopDownstream() {
        downstream.stop(0);
    }

    @DynamicPropertySource
    static void routeEverythingToTheStub(DynamicPropertyRegistry registry) {
        String stub = "http://localhost:" + downstream.getAddress().getPort();
        // These are the exact placeholder names the route config uses.
        // A dotted equivalent would not resolve: ${CLIENTS_ORDER_BASE_URL} is
        // looked up by name, and relaxed binding runs the other way, mapping a
        // dotted property onto an environment variable rather than back.
        registry.add("CLIENTS_ORDER_BASE_URL", () -> stub);
        registry.add("CLIENTS_INVENTORY_BASE_URL", () -> stub);
        registry.add("CLIENTS_PROVISIONING_BASE_URL", () -> stub);
    }

    @BeforeEach
    void clearRecordings() {
        receivedPaths.clear();
        receivedRequestIds.clear();
    }

    @Test
    void routesActivationsAndStripsTheApiPrefix() {
        get("/api/activations/abc", null);

        assertThat(receivedPaths).containsExactly("/activations/abc");
    }

    @Test
    void routesReservationsAndProvisions() {
        get("/api/reservations/abc", null);
        get("/api/provisions/abc", null);

        assertThat(receivedPaths).containsExactly("/reservations/abc", "/provisions/abc");
    }

    @Test
    void generatesARequestIdAndForwardsItDownstream() {
        get("/api/activations/abc", null);

        assertThat(receivedRequestIds).hasSize(1);
        assertThat(receivedRequestIds.get(0))
            .as("the downstream service must be able to log the same id as the gateway")
            .isNotBlank();
    }

    @Test
    void forwardsAnIncomingRequestIdUnchanged() {
        get("/api/activations/abc", "caller-supplied-id");

        assertThat(receivedRequestIds).containsExactly("caller-supplied-id");
    }

    private void get(String path, String requestId) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path));
            if (requestId != null) {
                request.header("X-Request-Id", requestId);
            }
            this.http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        catch (Exception ex) {
            throw new IllegalStateException("gateway call failed", ex);
        }
    }
}
