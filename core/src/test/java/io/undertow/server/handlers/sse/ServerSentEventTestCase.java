/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.server.handlers.sse;

import io.undertow.server.handlers.encoding.ContentEncodingRepository;
import io.undertow.server.handlers.encoding.DeflateEncodingProvider;
import io.undertow.server.handlers.encoding.EncodingHandler;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.HttpClientUtils;
import io.undertow.testutils.TestHttpClient;
import io.undertow.util.StatusCodes;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DecompressingHttpClient;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xnio.IoUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Stuart Douglas
 */
@RunWith(DefaultServer.class)
public class ServerSentEventTestCase {


    @Test
    public void testSSE() throws IOException {
        TestHttpClient client = new TestHttpClient();
        try {
            DefaultServer.setRootHandler(new ServerSentEventHandler(new ServerSentEventConnectionCallback() {
                @Override
                public void connected(ServerSentEventConnection connection, String lastEventId) {
                    connection.send("msg 1", new ServerSentEventConnection.EventCallback() {
                        @Override
                        public void done(ServerSentEventConnection connection, String data, String event, String id) {
                            connection.send("msg 2", new ServerSentEventConnection.EventCallback() {
                                @Override
                                public void done(ServerSentEventConnection connection, String data, String event, String id) {
                                    IoUtils.safeClose(connection);
                                }

                                @Override
                                public void failed(ServerSentEventConnection connection, String data, String event, String id, IOException e) {
                                    e.printStackTrace();
                                    IoUtils.safeClose(connection);
                                }
                            });
                        }

                        @Override
                        public void failed(ServerSentEventConnection connection, String data, String event, String id, IOException e) {
                            e.printStackTrace();
                            IoUtils.safeClose(connection);
                        }
                    });
                }
            }));

            HttpGet get = new HttpGet(DefaultServer.getDefaultServerURL() + "/");
            HttpResponse result = client.execute(get);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            final String response = HttpClientUtils.readResponse(result);

            Assert.assertEquals("data:msg 1\n\ndata:msg 2\n\n", response);

        } finally {
            client.getConnectionManager().shutdown();
        }
    }



    @Test
    public void testProgressiveSSEWithCompression() throws IOException {
        final AtomicReference<ServerSentEventConnection> connectionReference = new AtomicReference<>();
        DecompressingHttpClient client = new DecompressingHttpClient(new TestHttpClient());
        try {

            DefaultServer.setRootHandler(new EncodingHandler(new ContentEncodingRepository()
                    .addEncodingHandler("deflate", new DeflateEncodingProvider(), 50))
                    .setNext(new ServerSentEventHandler(new ServerSentEventConnectionCallback() {
                        @Override
                        public void connected(ServerSentEventConnection connection, String lastEventId) {
                            connectionReference.set(connection);
                            connection.send("msg 1", new ServerSentEventConnection.EventCallback() {
                                @Override
                                public void done(ServerSentEventConnection connection, String data, String event, String id) {

                                }

                                @Override
                                public void failed(ServerSentEventConnection connection, String data, String event, String id, IOException e) {
                                    e.printStackTrace();
                                    IoUtils.safeClose(connection);
                                }
                            });
                        }
                    })));

            HttpGet get = new HttpGet(DefaultServer.getDefaultServerURL() + "/");
            HttpResponse result = client.execute(get);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            InputStream stream = result.getEntity().getContent();
            assertData(stream, "data:msg 1\n\n");
            connectionReference.get().send("msg 2");
            assertData(stream, "data:msg 2\n\n");
            connectionReference.get().close();
        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    private void assertData(InputStream stream, String data) throws IOException {
        byte[] d = data.getBytes(StandardCharsets.US_ASCII);
        int index = 0;
        byte[] buf = new byte[100];
        while (index < d.length) {
            int r = stream.read(buf);
            if(r == -1 ){
                Assert.fail("unexpected end of stream at index " + index);
            }
            int rem = d.length - index;
            if(r > rem) {
                Assert.fail("Read too much data index: " + index + " expected: " + data + " read: " + new String(buf, 0 , r));
            }
            for(int i = 0; i < r; ++i) {
                Assert.assertEquals("Comparison failed " + "index: " + index + " expected: " + data + " read: " + new String(buf, 0 , r),d[index++], buf[i]);
            }
        }
    }


    @Test
    public void testLargeMessage() throws IOException {
        TestHttpClient client = new TestHttpClient();
        final StringBuilder sb = new StringBuilder();
        for(int i =0; i < 10000; ++i) {
            sb.append("hello world ");
        }
        try {
            DefaultServer.setRootHandler(new ServerSentEventHandler(new ServerSentEventConnectionCallback() {
                @Override
                public void connected(ServerSentEventConnection connection, String lastEventId) {
                    connection.send(sb.toString(), new ServerSentEventConnection.EventCallback() {
                        @Override
                        public void done(ServerSentEventConnection connection, String data, String event, String id) {
                            IoUtils.safeClose(connection);
                        }

                        @Override
                        public void failed(ServerSentEventConnection connection, String data, String event, String id, IOException e) {
                            e.printStackTrace();
                            IoUtils.safeClose(connection);
                        }
                    });
                }
            }));

            HttpGet get = new HttpGet(DefaultServer.getDefaultServerURL() + "/");
            HttpResponse result = client.execute(get);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            final String response = HttpClientUtils.readResponse(result);

            Assert.assertEquals("data:" + sb.toString() + "\n\n", response);

        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    @Test
    public void testConnectionFail() throws IOException, InterruptedException {

        final Socket socket = new Socket(DefaultServer.getHostAddress("default"), DefaultServer.getHostPort("default"));
        final CountDownLatch latch = new CountDownLatch(1);
        DefaultServer.setRootHandler(new ServerSentEventHandler(new ServerSentEventConnectionCallback() {
            @Override
            public void connected(ServerSentEventConnection connection, String lastEventId) {
                do {
                    connection.send("hello", new ServerSentEventConnection.EventCallback() {
                        @Override
                        public void done(ServerSentEventConnection connection, String data, String event, String id) {
                        }

                        @Override
                        public void failed(ServerSentEventConnection connection, String data, String event, String id, IOException e) {
                            latch.countDown();
                        }
                    });
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                } while (latch.getCount() > 0);
            }
        }));
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        out.write(("GET / HTTP/1.1\r\n\r\n").getBytes());
        out.flush();
        out.close();
        in.close();
        if(!latch.await(10, TimeUnit.SECONDS)) {
            Assert.fail();
        }
    }
}
