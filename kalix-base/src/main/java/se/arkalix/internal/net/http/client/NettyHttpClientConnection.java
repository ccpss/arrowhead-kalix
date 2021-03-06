package se.arkalix.internal.net.http.client;

import se.arkalix.dto.DtoWritable;
import se.arkalix.dto.DtoWriter;
import se.arkalix.dto.DtoWriteException;
import se.arkalix.internal.dto.binary.ByteBufWriter;
import se.arkalix.internal.net.http.HttpMediaTypes;
import se.arkalix.internal.util.concurrent.NettyFutures;
import se.arkalix.net.http.HttpVersion;
import se.arkalix.net.http.client.HttpClientConnection;
import se.arkalix.net.http.client.HttpClientRequest;
import se.arkalix.net.http.client.HttpClientResponse;
import se.arkalix.util.Result;
import se.arkalix.util.annotation.Internal;
import se.arkalix.util.concurrent.Future;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import static se.arkalix.internal.net.http.NettyHttpAdapters.adapt;
import static io.netty.handler.codec.http.HttpHeaderNames.*;

@Internal
public class NettyHttpClientConnection implements HttpClientConnection {
    private final Certificate[] certificateChain;
    private final Channel channel;
    private final Queue<FutureResponse> pendingResponseQueue = new LinkedList<>();

    public NettyHttpClientConnection(
        final Channel channel,
        final Certificate[] certificateChain)
    {
        this.channel = Objects.requireNonNull(channel, "Expected channel");
        this.certificateChain = certificateChain;
    }

    @Override
    public InetSocketAddress remoteSocketAddress() {
        return (InetSocketAddress) channel.remoteAddress();
    }

    @Override
    public InetSocketAddress localSocketAddress() {
        return (InetSocketAddress) channel.localAddress();
    }

    @Override
    public Certificate[] certificateChain() {
        if (certificateChain == null) {
            throw new UnsupportedOperationException("Connection not secured;" +
                " no certificates are available");
        }
        return certificateChain;
    }

    @Override
    public boolean isLive() {
        return channel.isActive();
    }

    @Override
    public Future<HttpClientResponse> send(final HttpClientRequest request) {
        return send(request, true);
    }

    private Future<HttpClientResponse> send(final HttpClientRequest request, final boolean keepAlive) {
        try {
            writeRequestToChannel(request, keepAlive);
        }
        catch (final Throwable throwable) {
            return Future.failure(throwable);
        }
        final var pendingResponse = new FutureResponse();
        pendingResponseQueue.add(pendingResponse);
        return pendingResponse;
    }

    @Override
    public Future<HttpClientResponse> sendAndClose(final HttpClientRequest request) {
        return send(request, false)
            .flatMapResult(result -> {
                if (channel.isActive()) {
                    return close().mapResult(ignored -> result);
                }
                return Future.of(result);
            });
    }

    private void writeRequestToChannel(final HttpClientRequest request, final boolean keepAlive) throws DtoWriteException, IOException {
        final var body = request.body().orElse(null);
        final var headers = request.headers().unwrap();
        final var method = adapt(request.method().orElseThrow(() -> new IllegalArgumentException("Expected method")));

        final var queryStringEncoder = new QueryStringEncoder(request.uri()
            .orElseThrow(() -> new IllegalArgumentException("Expected uri")));

        for (final var entry : request.queryParameters().entrySet()) {
            final var name = entry.getKey();
            for (final var value : entry.getValue()) {
                queryStringEncoder.addParam(name, value);
            }
        }

        final var uri = queryStringEncoder.toString();
        final var version = adapt(request.version().orElse(HttpVersion.HTTP_11));

        headers.set(HOST, remoteSocketAddress().getHostString());
        HttpUtil.setKeepAlive(headers, version, keepAlive);

        final ByteBuf content;
        if (body == null) {
            content = Unpooled.EMPTY_BUFFER;
        }
        else if (body instanceof byte[]) {
            content = Unpooled.wrappedBuffer((byte[]) body);
        }
        else if (body instanceof DtoWritable) {
            final var contentType = headers.get(CONTENT_TYPE);
            final var encoding = request.encoding().orElseThrow(() -> new IllegalStateException("" +
                "DTO body set without encoding being specified"));

            content = channel.alloc().buffer();
            DtoWriter.write((DtoWritable) body, encoding, new ByteBufWriter(content));

            final var mediaType = HttpMediaTypes.toMediaType(encoding);
            if (!headers.contains(ACCEPT)) {
                headers.set(ACCEPT, mediaType);
            }
            if (contentType == null || contentType.isBlank()) {
                headers.set(CONTENT_TYPE, mediaType);
            }
        }
        else if (body instanceof Path) {
            final var path = (Path) body;
            final var file = new RandomAccessFile(path.toFile(), "r");
            final var length = file.length();

            headers.set(CONTENT_LENGTH, length);

            channel.write(new DefaultHttpRequest(version, method, uri, headers));
            channel.write(new DefaultFileRegion(file.getChannel(), 0, length));
            channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            return;
        }
        else if (body instanceof String) {
            final var charset = HttpUtil.getCharset(headers.get("content-type"), StandardCharsets.UTF_8);
            content = Unpooled.wrappedBuffer(((String) body).getBytes(charset));
        }
        else {
            throw new IllegalStateException("Invalid response body supplied \"" + body + "\"");
        }
        headers.set(CONTENT_LENGTH, content.readableBytes());

        channel.writeAndFlush(new DefaultFullHttpRequest(version, method, uri, content, headers,
            EmptyHttpHeaders.INSTANCE));
    }

    @Override
    public Future<?> close() {
        return NettyFutures.adapt(channel.close());
    }

    public boolean onResponseResult(final Result<HttpClientResponse> result) {
        final var pendingResponse = pendingResponseQueue.poll();
        if (pendingResponse == null) {
            throw new IllegalStateException("No pending response available", result.isSuccess()
                ? null
                : result.fault());
        }
        return pendingResponse.setResult(result);
    }

    private static class FutureResponse implements Future<HttpClientResponse> {
        private Consumer<Result<HttpClientResponse>> consumer = null;
        private boolean isDone = false;
        private Result<HttpClientResponse> pendingResult = null;

        @Override
        public void onResult(final Consumer<Result<HttpClientResponse>> consumer) {
            if (isDone) {
                return;
            }
            if (pendingResult != null) {
                consumer.accept(pendingResult);
                isDone = true;
            }
            else {
                this.consumer = consumer;
            }
        }

        /*
         * Cancelling simply causes the response to be ignored. If not wanting
         * the response to be received at all the client must be closed.
         */
        @Override
        public void cancel(final boolean mayInterruptIfRunning) {
            if (isDone) {
                return;
            }
            setResult(Result.failure(new CancellationException()));
        }

        public boolean setResult(final Result<HttpClientResponse> result) {
            if (isDone) {
                return false;
            }
            if (consumer != null) {
                consumer.accept(result);
                isDone = true;
            }
            else {
                pendingResult = result;
            }
            return true;
        }
    }
}
