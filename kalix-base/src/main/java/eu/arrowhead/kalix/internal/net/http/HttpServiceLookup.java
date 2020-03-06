package eu.arrowhead.kalix.internal.net.http;

import eu.arrowhead.kalix.net.http.service.HttpService;

import java.util.Optional;

/**
 * A function useful for determining what {@link HttpService} to use for
 * handling a request with some given path.
 */
@FunctionalInterface
public interface HttpServiceLookup {
    /**
     * Tries to determine what {@link HttpService} to use for handling an
     * incoming HTTP request with the given {@code path}.
     *
     * @param path Path of incoming HTTP request to be handled by service.
     * @return Designated service, if any.
     */
    Optional<HttpService> getServiceByPath(final String path);
}
