package com.infrawatch.client;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;

@ApplicationScoped
public class DatadogAuthHeaderFactory implements ClientHeadersFactory {

    @ConfigProperty(name = "infrawatch.datadog.api-key", defaultValue = "")
    String apiKey;

    @ConfigProperty(name = "infrawatch.datadog.app-key", defaultValue = "")
    String appKey;

    @Override
    public MultivaluedMap<String, String> update(
        MultivaluedMap<String, String> incomingHeaders,
        MultivaluedMap<String, String> clientOutgoingHeaders
    ) {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.add("DD-API-KEY",         apiKey);
        headers.add("DD-APPLICATION-KEY", appKey);
        return headers;
    }
}
