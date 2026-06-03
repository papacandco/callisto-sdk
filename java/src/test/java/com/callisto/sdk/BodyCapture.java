package com.callisto.sdk;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/** Extracts the serialized body string from an {@link HttpRequest}'s body publisher (for tests). */
final class BodyCapture {

    private BodyCapture() {
    }

    static String extract(HttpRequest request) {
        return request.bodyPublisher().map(BodyCapture::drain).orElse(null);
    }

    private static String drain(HttpRequest.BodyPublisher publisher) {
        List<ByteBuffer> buffers = new ArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                ByteBuffer copy = ByteBuffer.allocate(item.remaining());
                copy.put(item);
                copy.flip();
                buffers.add(copy);
            }

            @Override
            public void onError(Throwable throwable) {
                done.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }
        });
        done.join();
        int total = buffers.stream().mapToInt(ByteBuffer::remaining).sum();
        ByteBuffer all = ByteBuffer.allocate(total);
        buffers.forEach(all::put);
        return new String(all.array(), StandardCharsets.UTF_8);
    }
}
