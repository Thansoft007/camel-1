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
package org.apache.camel.processor;

import org.apache.camel.CamelExecutionException;
import org.apache.camel.ContextTestSupport;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.Test;

public class OnCompletionTest extends ContextTestSupport {

    @Test
    public void testSynchronizeComplete() throws Exception {
        getMockEndpoint("mock:sync").expectedBodiesReceived("Bye World");
        getMockEndpoint("mock:sync").expectedPropertyReceived(Exchange.ON_COMPLETION, true);

        MockEndpoint mock = getMockEndpoint("mock:result");
        mock.expectedBodiesReceived("Bye World");

        template.sendBody("direct:start", "Hello World");

        assertMockEndpointsSatisfied();
    }

    @Test
    public void testSynchronizeFailure() throws Exception {
        getMockEndpoint("mock:sync").expectedMessageCount(1);
        getMockEndpoint("mock:sync").expectedPropertyReceived(Exchange.ON_COMPLETION, true);
        getMockEndpoint("mock:sync").message(0).exchangeProperty(Exchange.EXCEPTION_CAUGHT).isInstanceOf(IllegalArgumentException.class);

        MockEndpoint mock = getMockEndpoint("mock:result");
        mock.expectedMessageCount(0);

        try {
            template.sendBody("direct:start", "Kabom");
            fail("Should throw exception");
        } catch (CamelExecutionException e) {
            assertEquals("Kabom", e.getCause().getMessage());
        }

        assertMockEndpointsSatisfied();
    }

    @Override
    protected RouteBuilder createRouteBuilder() throws Exception {
        return new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                // START SNIPPET: e1
                from("direct:start")
                    .onCompletion()
                        // this route is only invoked when the original route is complete as a kind
                        // of completion callback
                        .to("log:sync")
                        .to("mock:sync")
                    // must use end to denote the end of the onCompletion route
                    .end()
                    // here the original route contiues
                    .process(new MyProcessor())
                    .to("mock:result");
                // END SNIPPET: e1
            }
        };
    }

    public static class MyProcessor implements Processor {

        public MyProcessor() {
        }

        @Override
        public void process(Exchange exchange) throws Exception {
            if ("Kabom".equals(exchange.getIn().getBody())) {
                throw new IllegalArgumentException("Kabom");
            }
            exchange.getIn().setBody("Bye World");
        }
    }
}
