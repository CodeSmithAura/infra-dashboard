package com.infrawatch.client;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Injects Basic Auth header for every ServiceNow API call.
 */
@ApplicationScoped
public class ServiceNowAuthHeaderFactory implements ClientHeadersFactory {

    @ConfigProperty(name = "infrawatch.servicenow.username", defaultValue = "admin")
    String username;

    @ConfigProperty(name = "infrawatch.servicenow.password", defaultValue = "")
    String password;

    @Override
    public MultivaluedMap<String, String> update(
        MultivaluedMap<String, String> incomingHeaders,
        MultivaluedMap<String, String> clientOutgoingHeaders
    ) {
        String encoded = Base64.getEncoder()
            .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.add("Authorization", "Basic " + encoded);
        headers.add("Accept", "application/json");
        return headers;
    }
}
