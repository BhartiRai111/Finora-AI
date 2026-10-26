package com.finora.apigateway;

import com.finora.testkit.StubServer;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;

/** One real loopback HTTP stub per downstream service, shared by the gateway tests in this JVM. */
final class Stubs {

    static final List<String> SERVICES = List.of("identity-access", "finance", "document", "ingestion-integration",
            "reporting", "intelligence", "platform", "audit");
    private static final Map<String, StubServer> SERVERS = new LinkedHashMap<>();
    /** A port that nothing listens on: models a service that is down. */
    static final int DEAD_PORT = freePort();

    static {
        for (String s : SERVICES) {
            SERVERS.put(s, new StubServer());
        }
    }

    private Stubs() {
    }

    static StubServer get(String service) {
        return SERVERS.get(service);
    }

    static void resetAll() {
        SERVERS.values().forEach(StubServer::reset);
    }

    static void register(DynamicPropertyRegistry registry) {
        SERVERS.forEach((name, stub) -> registry.add("finora.gateway.services." + name, stub::baseUrl));
    }

    static void registerWithDown(DynamicPropertyRegistry registry, String downService) {
        register(registry);
        registry.add("finora.gateway.services." + downService, () -> "http://127.0.0.1:" + DEAD_PORT);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
