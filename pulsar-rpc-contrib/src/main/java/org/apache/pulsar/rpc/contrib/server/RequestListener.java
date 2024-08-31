/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pulsar.rpc.contrib.server;

import static org.apache.pulsar.rpc.contrib.common.Constants.REPLY_TOPIC;
import static org.apache.pulsar.rpc.contrib.common.Constants.REQUEST_TIMEOUT_MILLIS;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageListener;

@Slf4j
@RequiredArgsConstructor
public class RequestListener<T, V> implements MessageListener<T> {
    private final Function<T, CompletableFuture<V>> requestFunction;
    private final ReplySender<T, V> sender;
    private final BiConsumer<String, T> rollBackFunction;

    @Override
    public void received(Consumer<T> consumer, Message<T> msg) {
        long replyTimeout = Long.parseLong(msg.getProperty(REQUEST_TIMEOUT_MILLIS))
                - (System.currentTimeMillis() - msg.getPublishTime());
        if (replyTimeout <= 0) {
            consumer.acknowledgeAsync(msg);
            return;
        }

        String correlationId = msg.getKey();
        String replyTopic = msg.getProperty(REPLY_TOPIC);
        T value = msg.getValue();

        try {
            requestFunction.apply(value)
                    .orTimeout(replyTimeout, TimeUnit.MILLISECONDS)
                    .thenAccept(reply -> {
                        sender.sendReply(replyTopic, correlationId, reply, value);
                    })
                    .get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                log.error("[{}] Timeout and rollback", correlationId, e);
                rollBackFunction.accept(correlationId, value);
            } else {
                log.error("[{}] Error processing request", correlationId, e);
                sender.sendErrorReply(replyTopic, correlationId,
                        cause.getClass().getName() + ": " + cause.getMessage(), value);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
