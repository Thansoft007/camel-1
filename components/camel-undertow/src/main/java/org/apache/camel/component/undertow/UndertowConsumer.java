/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.undertow;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Collection;

import io.undertow.Handlers;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.accesslog.AccessLogHandler;
import io.undertow.server.handlers.accesslog.AccessLogReceiver;
import io.undertow.server.handlers.accesslog.JBossLoggingAccessLogReceiver;
import io.undertow.server.handlers.form.EagerFormParsingHandler;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import io.undertow.util.MimeMappings;
import io.undertow.util.StatusCodes;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.spi.WebSocketHttpExchange;

import org.apache.camel.AsyncCallback;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.NoTypeConversionAvailableException;
import org.apache.camel.Processor;
import org.apache.camel.TypeConverter;
import org.apache.camel.component.undertow.UndertowConstants.EventType;
import org.apache.camel.component.undertow.handlers.CamelWebSocketHandler;
import org.apache.camel.support.DefaultConsumer;
import org.apache.camel.util.CollectionStringBuffer;
import org.apache.camel.util.IOHelper;
import org.apache.camel.util.ObjectHelper;

/**
 * The Undertow consumer which is also an Undertow HttpHandler implementation to handle incoming request.
 */
public class UndertowConsumer extends DefaultConsumer implements HttpHandler {

    private CamelWebSocketHandler webSocketHandler;

    public UndertowConsumer(UndertowEndpoint endpoint, Processor processor) {
        super(endpoint, processor);
    }

    @Override
    public UndertowEndpoint getEndpoint() {
        return (UndertowEndpoint) super.getEndpoint();
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();
        final UndertowEndpoint endpoint = getEndpoint();
        if (endpoint.isWebSocket()) {
            /*
             * note that the new CamelWebSocketHandler() we pass to registerEndpoint() does not necessarily have to be
             * the same instance that is returned from there
             */
            this.webSocketHandler = (CamelWebSocketHandler) endpoint.getComponent().registerEndpoint(endpoint.getHttpHandlerRegistrationInfo(), endpoint.getSslContext(), new CamelWebSocketHandler());
            this.webSocketHandler.setConsumer(this);
        } else {
            // allow for HTTP 1.1 continue
            HttpHandler httpHandler = new EagerFormParsingHandler().setNext(UndertowConsumer.this);
            if (endpoint.getAccessLog()) {
                AccessLogReceiver accessLogReciever = null;
                if (endpoint.getAccessLogReceiver() != null) {
                    accessLogReciever = endpoint.getAccessLogReceiver();
                } else {
                    accessLogReciever = new JBossLoggingAccessLogReceiver();
                }
                httpHandler = new AccessLogHandler(httpHandler,
                        accessLogReciever,
                        "common",
                        AccessLogHandler.class.getClassLoader());
            }
            endpoint.getComponent().registerEndpoint(endpoint.getHttpHandlerRegistrationInfo(), endpoint.getSslContext(), Handlers.httpContinueRead(
                    // wrap with EagerFormParsingHandler to enable undertow form parsers
                    httpHandler));
        }
    }

    @Override
    protected void doStop() throws Exception {
        super.doStop();
        if (this.webSocketHandler != null) {
            this.webSocketHandler.setConsumer(null);
        }
        UndertowEndpoint endpoint = getEndpoint();
        endpoint.getComponent().unregisterEndpoint(endpoint.getHttpHandlerRegistrationInfo(), endpoint.getSslContext());
    }

    @Override
    public void handleRequest(HttpServerExchange httpExchange) throws Exception {
        HttpString requestMethod = httpExchange.getRequestMethod();

        if (Methods.OPTIONS.equals(requestMethod) && !getEndpoint().isOptionsEnabled()) {
            CollectionStringBuffer csb = new CollectionStringBuffer(",");

            Collection<HttpHandlerRegistrationInfo> handlers = getEndpoint().getComponent().getHandlers();
            for (HttpHandlerRegistrationInfo reg : handlers) {
                URI uri = reg.getUri();
                // what other HTTP methods may exists for the same path
                if (reg.getMethodRestrict() != null && getEndpoint().getHttpURI().equals(uri)) {
                    String restrict = reg.getMethodRestrict();
                    if (restrict.endsWith(",OPTIONS")) {
                        restrict = restrict.substring(0, restrict.length() - 8);
                    }
                    csb.append(restrict);
                }
            }
            String allowedMethods = csb.toString();
            if (ObjectHelper.isEmpty(allowedMethods)) {
                allowedMethods = getEndpoint().getHttpMethodRestrict();
            }
            if (ObjectHelper.isEmpty(allowedMethods)) {
                allowedMethods = "GET,HEAD,POST,PUT,DELETE,TRACE,OPTIONS,CONNECT,PATCH";
            }
            if (!allowedMethods.contains("OPTIONS")) {
                allowedMethods = allowedMethods + ",OPTIONS";
            }
            //return list of allowed methods in response headers
            httpExchange.setStatusCode(StatusCodes.OK);
            httpExchange.getResponseHeaders().put(ExchangeHeaders.CONTENT_LENGTH, 0);
            // do not include content-type as that would indicate to the caller that we can only do text/plain
            httpExchange.getResponseHeaders().put(Headers.ALLOW, allowedMethods);
            httpExchange.endExchange();
            return;
        }

        //perform blocking operation on exchange
        if (httpExchange.isInIoThread()) {
            httpExchange.dispatch(this);
            return;
        }

        //create new Exchange
        //binding is used to extract header and payload(if available)
        Exchange camelExchange = getEndpoint().createExchange(httpExchange);

        //Unit of Work to process the Exchange
        createUoW(camelExchange);
        try {
            getProcessor().process(camelExchange);
        } catch (Exception e) {
            getExceptionHandler().handleException(e);
        } finally {
            doneUoW(camelExchange);
        }

        sendResponse(httpExchange, camelExchange);
    }

    private void sendResponse(HttpServerExchange httpExchange, Exchange camelExchange) throws IOException, NoTypeConversionAvailableException {
        Object body = getResponseBody(httpExchange, camelExchange);

        if (body == null) {
            log.trace("No payload to send as reply for exchange: {}", camelExchange);
            httpExchange.getResponseHeaders().put(ExchangeHeaders.CONTENT_TYPE, MimeMappings.DEFAULT_MIME_MAPPINGS.get("txt"));
            httpExchange.getResponseSender().send("No response available");
            return;
        }

        if (getEndpoint().isUseStreaming() && (body instanceof InputStream)) {
            httpExchange.startBlocking();
            try (InputStream input = (InputStream) body;
                 OutputStream output = httpExchange.getOutputStream()) {
                // flush on each write so that it won't cause OutOfMemoryError
                IOHelper.copy(input, output, IOHelper.DEFAULT_BUFFER_SIZE, true);
            }
        } else {
            TypeConverter tc = getEndpoint().getCamelContext().getTypeConverter();
            ByteBuffer bodyAsByteBuffer = tc.mandatoryConvertTo(ByteBuffer.class, body);
            httpExchange.getResponseSender().send(bodyAsByteBuffer);
        }
    }

    /**
     * Create an {@link Exchange} from the associated {@link UndertowEndpoint} and set the {@code in} {@link Message}'s
     * body to the given {@code message} and {@link UndertowConstants#CONNECTION_KEY} header to the given
     * {@code connectionKey}.
     *
     * @param connectionKey an identifier of {@link WebSocketChannel} through which the {@code message} was received
     * @param channel       the {@link WebSocketChannel} through which the {@code message} was received
     * @param message       the message received via the {@link WebSocketChannel}
     */
    public void sendMessage(final String connectionKey, WebSocketChannel channel, final Object message) {

        final Exchange exchange = getEndpoint().createExchange();

        // set header and body
        exchange.getIn().setHeader(UndertowConstants.CONNECTION_KEY, connectionKey);
        if (channel != null) {
            exchange.getIn().setHeader(UndertowConstants.CHANNEL, channel);
        }
        exchange.getIn().setBody(message);

        // send exchange using the async routing engine
        getAsyncProcessor().process(exchange, new AsyncCallback() {
            public void done(boolean doneSync) {
                if (exchange.getException() != null) {
                    getExceptionHandler().handleException("Error processing exchange", exchange,
                            exchange.getException());
                }
            }
        });
    }

    /**
     * Send a notification related a WebSocket peer.
     *
     * @param connectionKey     of WebSocket peer
     * @param transportExchange the exchange for the websocket transport, only available for ON_OPEN events
     * @param channel           the {@link WebSocketChannel} through which the {@code message} was received
     * @param eventType         the type of the event
     */
    public void sendEventNotification(String connectionKey, WebSocketHttpExchange transportExchange, WebSocketChannel channel, EventType eventType) {
        final Exchange exchange = getEndpoint().createExchange();

        final Message in = exchange.getIn();
        in.setHeader(UndertowConstants.CONNECTION_KEY, connectionKey);
        in.setHeader(UndertowConstants.EVENT_TYPE, eventType.getCode());
        in.setHeader(UndertowConstants.EVENT_TYPE_ENUM, eventType);
        if (channel != null) {
            in.setHeader(UndertowConstants.CHANNEL, channel);
        }
        if (transportExchange != null) {
            in.setHeader(UndertowConstants.EXCHANGE, transportExchange);
        }
        // send exchange using the async routing engine
        getAsyncProcessor().process(exchange, new AsyncCallback() {
            public void done(boolean doneSync) {
                if (exchange.getException() != null) {
                    getExceptionHandler().handleException("Error processing exchange", exchange, exchange.getException());
                }
            }
        });
    }

    private Object getResponseBody(HttpServerExchange httpExchange, Exchange camelExchange) throws IOException {
        Object result;
        if (camelExchange.hasOut()) {
            result = getEndpoint().getUndertowHttpBinding().toHttpResponse(httpExchange, camelExchange.getOut());
        } else {
            result = getEndpoint().getUndertowHttpBinding().toHttpResponse(httpExchange, camelExchange.getIn());
        }
        return result;
    }

}
