/*
 * Copyright 2002-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.graphql.web.webflux;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.graphql.web.BookTestUtils;
import org.springframework.graphql.web.ConsumeOneAndNeverCompleteInterceptor;
import org.springframework.graphql.web.WebInterceptor;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.map;

/**
 * Unit tests for {@link GraphQlWebSocketHandler}.
 */
public class GraphQlWebSocketHandlerTests {

	private static final Jackson2JsonDecoder decoder = new Jackson2JsonDecoder();


	@Test
	void query() {
		TestWebSocketSession session = handle(Flux.just(
				toWebSocketMessage("{\"type\":\"connection_init\"}"),
				toWebSocketMessage(BookTestUtils.BOOK_QUERY)));

		StepVerifier.create(session.getOutput())
				.consumeNextWith(message -> assertMessageType(message, "connection_ack"))
				.consumeNextWith(message ->
						assertThat(decode(message))
								.hasSize(3)
								.containsEntry("id", BookTestUtils.SUBSCRIPTION_ID)
								.containsEntry("type", "next")
								.extractingByKey("payload", as(map(String.class, Object.class)))
								.extractingByKey("data", as(map(String.class, Object.class)))
								.extractingByKey("bookById", as(map(String.class, Object.class)))
								.containsEntry("name", "Nineteen Eighty-Four"))
				.consumeNextWith(message -> assertMessageType(message, "complete"))
				.verifyComplete();
	}

	@Test
	void subscription() {
		TestWebSocketSession session = handle(Flux.just(
				toWebSocketMessage("{\"type\":\"connection_init\"}"),
				toWebSocketMessage(BookTestUtils.BOOK_SUBSCRIPTION)));

		BiConsumer<WebSocketMessage, String> bookPayloadAssertion = (message, bookId) ->
				assertThat(decode(message))
						.hasSize(3)
						.containsEntry("id", BookTestUtils.SUBSCRIPTION_ID)
						.containsEntry("type", "next")
						.extractingByKey("payload", as(map(String.class, Object.class)))
						.extractingByKey("data", as(map(String.class, Object.class)))
						.extractingByKey("bookSearch", as(map(String.class, Object.class)))
						.containsEntry("id", bookId);

		StepVerifier.create(session.getOutput())
				.consumeNextWith(message -> assertMessageType(message, "connection_ack"))
				.consumeNextWith(message -> bookPayloadAssertion.accept(message, "1"))
				.consumeNextWith(message -> bookPayloadAssertion.accept(message, "5"))
				.consumeNextWith(message -> assertMessageType(message, "complete"))
				.verifyComplete();
	}

	@Test
	void unauthorizedWithoutMessageType() {
		TestWebSocketSession session = handle(Flux.just(
				toWebSocketMessage("{\"type\":\"connection_init\"}"),
				toWebSocketMessage("{\"id\":\"" + BookTestUtils.SUBSCRIPTION_ID + "\"}")));

		StepVerifier.create(session.getOutput())
				.consumeNextWith(message -> assertMessageType(message, "connection_ack"))
				.verifyComplete();

		StepVerifier.create(session.closeStatus())
				.expectNext(new CloseStatus(4400, "Invalid message"))
				.verifyComplete();
	}

	@Test
	void invalidMessageWithoutId() {
		Flux<WebSocketMessage> input = Flux.just(
				toWebSocketMessage("{\"type\":\"connection_init\"}"),
				toWebSocketMessage("{\"type\":\"subscribe\"}"));  // No message id

		TestWebSocketSession session = handle(input);

		StepVerifier.create(session.getOutput())
				.consumeNextWith(message -> assertMessageType(message, "connection_ack"))
				.verifyComplete();

		StepVerifier.create(session.closeStatus())
				.expectNext(new CloseStatus(4400, "Invalid message"))
				.verifyComplete();
	}

	@Test
	void unauthorizedWithoutConnectionInit() {
		TestWebSocketSession session = handle(Flux.just(toWebSocketMessage(BookTestUtils.BOOK_SUBSCRIPTION)));

		StepVerifier.create(session.getOutput()).verifyComplete();
		StepVerifier.create(session.closeStatus())
				.expectNext(new CloseStatus(4401, "Unauthorized"))
				.verifyComplete();
	}

	@Test
	void tooManyConnectionInitRequests() {
		TestWebSocketSession session = handle(Flux.just(
				toWebSocketMessage("{\"type\":\"connection_init\"}"),
				toWebSocketMessage("{\"type\":\"connection_init\"}")));

		StepVerifier.create(session.getOutput())
				.consumeNextWith(message -> assertMessageType(message, "connection_ack"))
				.verifyComplete();

		StepVerifier.create(session.closeStatus())
				.expectNext(new CloseStatus(4429, "Too many initialisation requests"))
				.verifyComplete();
	}

	@Test
	void connectionInitTimeout() {
		GraphQlWebSocketHandler handler = new GraphQlWebSocketHandler(
				BookTestUtils.initWebGraphQlHandler(), ServerCodecConfigurer.create(), Duration.ofMillis(50));

		TestWebSocketSession session = new TestWebSocketSession(Flux.empty());
		handler.handle(session).block();

		StepVerifier.create(session.closeStatus())
				.expectNext(new CloseStatus(4408, "Connection initialisation timeout"))
				.verifyComplete();
	}

	@Test
	void subscriptionExists() {
		TestWebSocketSession session = handle(Flux.just(
				toWebSocketMessage("{\"type\":\"connection_init\"}"),
				toWebSocketMessage(BookTestUtils.BOOK_SUBSCRIPTION),
				toWebSocketMessage(BookTestUtils.BOOK_SUBSCRIPTION)), new ConsumeOneAndNeverCompleteInterceptor());

		// Collect messages until session closed
		List<Map<String, Object>> messages = new ArrayList<>();
		session.getOutput().subscribe(message -> messages.add(decode(message)));

		StepVerifier.create(session.closeStatus())
				.expectNext(new CloseStatus(4409, "Subscriber for " + BookTestUtils.SUBSCRIPTION_ID + " already exists"))
				.verifyComplete();

		assertThat(messages.size()).isEqualTo(2);
		assertThat(messages.get(0).get("type")).isEqualTo("connection_ack");
		assertThat(messages.get(1).get("type")).isEqualTo("next");
	}

	@Test
	void clientCompletion() {
		Sinks.Many<WebSocketMessage> input = Sinks.many().unicast().onBackpressureBuffer();
		input.tryEmitNext(toWebSocketMessage("{\"type\":\"connection_init\"}"));
		input.tryEmitNext(toWebSocketMessage(BookTestUtils.BOOK_SUBSCRIPTION));

		TestWebSocketSession session =
				handle(input.asFlux(), new ConsumeOneAndNeverCompleteInterceptor());

		String completeMessage = "{\"id\":\"" + BookTestUtils.SUBSCRIPTION_ID + "\",\"type\":\"complete\"}";

		StepVerifier.create(session.getOutput())
				.consumeNextWith(message -> assertMessageType(message, "connection_ack"))
				.consumeNextWith(message -> assertMessageType(message, "next"))
				.then(() -> input.tryEmitNext(toWebSocketMessage(completeMessage)))
				.as("Second subscription with same id is possible only if the first was properly removed")
				.then(() -> input.tryEmitNext(toWebSocketMessage(BookTestUtils.BOOK_SUBSCRIPTION)))
				.consumeNextWith(message -> assertMessageType(message, "next"))
				.then(() -> input.tryEmitNext(toWebSocketMessage(completeMessage)))
				.verifyTimeout(Duration.ofMillis(500));
	}

	private TestWebSocketSession handle(Flux<WebSocketMessage> input, WebInterceptor... interceptors) {
		GraphQlWebSocketHandler handler = new GraphQlWebSocketHandler(
				BookTestUtils.initWebGraphQlHandler(interceptors),
				ServerCodecConfigurer.create(),
				Duration.ofSeconds(60));

		TestWebSocketSession session = new TestWebSocketSession(input);
		handler.handle(session).block();
		return session;
	}

	private static WebSocketMessage toWebSocketMessage(String data) {
		DataBuffer buffer = DefaultDataBufferFactory.sharedInstance.wrap(data.getBytes(StandardCharsets.UTF_8));
		return new WebSocketMessage(WebSocketMessage.Type.TEXT, buffer);
	}

	@SuppressWarnings({"unchecked", "ConstantConditions"})
	private Map<String, Object> decode(WebSocketMessage message) {
		return (Map<String, Object>) decoder.decode(
				DataBufferUtils.retain(message.getPayload()),
				GraphQlWebSocketHandler.MAP_RESOLVABLE_TYPE, null, Collections.emptyMap());
	}

	private void assertMessageType(WebSocketMessage message, String messageType) {
		Map<String, Object> map = decode(message);
		assertThat(map).containsEntry("type", messageType);
		if (!messageType.equals("connection_ack")) {
			assertThat(map).containsEntry("id", BookTestUtils.SUBSCRIPTION_ID);
		}
	}

}
