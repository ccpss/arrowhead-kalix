package se.arkalix.internal.net.http.service;

import se.arkalix.ArSystem;
import se.arkalix.description.ServiceDescription;
import se.arkalix.descriptor.EncodingDescriptor;
import se.arkalix.net.http.HttpStatus;
import se.arkalix.net.http.service.*;
import se.arkalix.security.access.AccessPolicy;
import se.arkalix.util.annotation.Internal;
import se.arkalix.util.concurrent.Future;

import java.util.*;

@Internal
public class HttpServiceInternal {
    private final AccessPolicy accessPolicy;
    private final ServiceDescription description;
    private final List<EncodingDescriptor> encodings;
    private final HttpRouteSequence[] routeSequences;

    public HttpServiceInternal(final ArSystem system, final HttpService service) {
        accessPolicy = Objects.requireNonNull(service.accessPolicy(), "Expected accessPolicy");
        description = service.describeAsIfProvidedBy(system);

        final var basePath = description.qualifier();
        if (!HttpPaths.isValidPathWithoutPercentEncodings(basePath)) {
            throw new IllegalArgumentException("HttpService basePath \"" +
                basePath + "\" must start with a forward slash (/) and then " +
                "contain only the following characters: A–Z a–z 0–9 " +
                "-._~!$%&'()*+,;/=:@");
        }
        if (basePath.length() > 1 && basePath.charAt(basePath.length() - 1) == '/') {
            throw new IllegalArgumentException("HttpService basePath may " +
                "not end with a forward slash (/) unless it is the root " +
                "path \"/\"");
        }

        encodings = service.encodings();
        if (encodings.size() == 0) {
            throw new IllegalArgumentException("Expected HttpService encodings.size() > 0");
        }

        final var routeSequenceFactory = new HttpRouteSequenceFactory(service.catchers(), service.validators());
        routeSequences = service.routes().stream()
            .sorted(HttpRoutables::compare)
            .map(routeSequenceFactory::createRouteSequenceFor)
            .toArray(HttpRouteSequence[]::new);
    }

    /**
     * @return Service name.
     */
    public String name() {
        return description.name();
    }

    /**
     * @return Base path that the paths of all requests targeted at this
     * service.
     */
    public String basePath() {
        return description.qualifier();
    }

    /**
     * @return Service access policy.
     */
    public AccessPolicy accessPolicy() {
        return accessPolicy;
    }

    /**
     * @return The encoding to use by default.
     */
    public EncodingDescriptor defaultEncoding() {
        return encodings.get(0);
    }

    /**
     * @return Data encodings supported by this service.
     */
    public List<EncodingDescriptor> encodings() {
        return encodings;
    }

    /**
     * Delegates handling of an {@link HttpServiceRequest} to this service.
     *
     * @param request  Incoming HTTP request.
     * @param response Modifiable HTTP response object, destined to be sent
     *                 back to the original request sender.
     * @return Future completed with {@code null} value when handling has
     * finished.
     */
    public Future<?> handle(final HttpServiceRequest request, final HttpServiceResponse response) {
        final var task = new HttpRouteTask.Builder()
            .basePath(basePath())
            .request(request)
            .response(response)
            .build();

        return trySequences(task, 0)
            .map(isHandled -> {
                if (!isHandled) {
                    response
                        .status(HttpStatus.NOT_FOUND)
                        .clearHeaders()
                        .clearBody();
                }
                return null;
            });
    }

    private Future<Boolean> trySequences(final HttpRouteTask task, final int index) {
        if (index >= routeSequences.length) {
            return Future.success(false);
        }
        final var routeSequence = routeSequences[index];
        return routeSequence.tryHandle(task)
            .flatMap(isHandled -> {
                if (isHandled) {
                    return Future.success(true);
                }
                return trySequences(task, index + 1);
            });
    }

    public ServiceDescription description() {
        return description;
    }
}
