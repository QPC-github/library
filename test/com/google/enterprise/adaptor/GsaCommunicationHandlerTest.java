// Copyright 2011 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.adaptor;

import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import org.junit.*;
import org.junit.rules.ExpectedException;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests for {@link GsaCommunicationHandler}.
 */
public class GsaCommunicationHandlerTest {
  /**
   * Generated with {@code keytool -alias notadaptor -keystore
   * test/com/google/enterprise/adaptor/GsaCommunicationHandlerTest.valid.jks
   * -storepass notchangeit -genkeypair -keyalg RSA -keypass notchangeit
   * -validity 7300 -dname "CN=Unknown, OU=Unknown, O=Unknown, L=Unknown,
   *  ST=Unknown, C=Unknown"}.
   */
  private static final String KEYSTORE_VALID_FILENAME
      = "test/com/google/enterprise/adaptor/GsaCommunicationHandlerTest.valid.jks";

  private Config config;
  private GsaCommunicationHandler gsa;
  private NullAdaptor adaptor = new NullAdaptor();
  private MockHttpServer mockServer = new MockHttpServer();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setup() {
    config = new Config();
    config.setValue("gsa.hostname", "localhost");
    // Let the OS choose the port
    config.setValue("server.port", "0");
    config.setValue("server.dashboardPort", "0");
    // As hard-coded in MockHttpExchange
    config.setValue("server.fullAccessHosts", "127.0.0.3");
    config.setValue("gsa.version", "7.2.0-0");
    gsa = new GsaCommunicationHandler(adaptor, config);
  }

  @After
  public void teardown() {
    gsa.stop(0, TimeUnit.SECONDS);
    gsa.teardown();
  }

  @Test
  public void testAdaptorContext() throws Exception {
    gsa = new GsaCommunicationHandler(new NullAdaptor(), config);
    AdaptorContext context = gsa.setup(mockServer, mockServer, null);
    assertSame(config, context.getConfig());
    assertNotNull(context.getDocIdPusher());
    assertNotNull(context.getDocIdEncoder());
    assertNotNull(context.getSensitiveValueDecoder());
    ExceptionHandler originalHandler
        = context.getGetDocIdsFullErrorHandler();
    ExceptionHandler replacementHandler
        = ExceptionHandlers.exponentialBackoffHandler(
            1, 1, TimeUnit.SECONDS);
    assertNotNull(originalHandler);
    context.setGetDocIdsFullErrorHandler(replacementHandler);
    assertSame(replacementHandler,
        context.getGetDocIdsFullErrorHandler());

    StatusSource source = new MockStatusSource("test",
        new MockStatus(Status.Code.NORMAL));
    context.addStatusSource(source);

    assertNotNull(context.createHttpContext("/test", new HttpHandler() {
      @Override
      public void handle(HttpExchange ex) {}
    }));
  }

  @Test
  public void testPollingIncrementalAdaptor() throws Exception {
    gsa = new GsaCommunicationHandler(new NullAdaptor(), config);
    AdaptorContext context = gsa.setup(mockServer, mockServer, null);
    final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<Object>(1);
    context.setPollingIncrementalLister(new PollingIncrementalLister() {
      @Override
      public void getModifiedDocIds(DocIdPusher pusher) {
        queue.offer(new Object());
      }
    });
    gsa.start();
    assertNotNull(queue.poll(1, TimeUnit.SECONDS));
  }

  @Test
  public void testFullPushAfterReload() throws Exception {
    NullAdaptor adaptor = new NullAdaptor();
    config.setValue("adaptor.pushDocIdsOnStartup", "false");
    gsa = new GsaCommunicationHandler(adaptor, config);
    gsa.setup(mockServer, mockServer, null);
    gsa.start();
    gsa.stop(1, TimeUnit.SECONDS);
    gsa.start();
    assertTrue(gsa.checkAndScheduleImmediatePushOfDocIds());
  }

  /**
   * Tests that HTTP serving not is started during setup().
   */
  @Test
  public void testNoServingBeforeStart() throws Exception {
    gsa = new GsaCommunicationHandler(adaptor, config);
    gsa.setup(mockServer, mockServer, null);
    assertEquals(0, mockServer.contexts.size());
  }

  @Test
  public void testMultiInstance() throws Exception {
    final Charset charset = Charset.forName("UTF-8");
    Adaptor adaptor = new NullAdaptor() {
      @Override
      public void getDocContent(Request req, Response resp)
          throws IOException {
        resp.getOutputStream().write(
            req.getDocId().getUniqueId().getBytes(charset));
      }
    };
    config.setValue("adaptor.pushDocIdsOnStartup", "false");
    gsa = new GsaCommunicationHandler(adaptor, config);
    gsa.setup(mockServer, mockServer, "/path");
    gsa.start();
    GsaCommunicationHandler gsa2 = new GsaCommunicationHandler(adaptor, config);
    gsa2.setup(mockServer, mockServer, "/path2");
    gsa2.start();

    try {
      MockHttpExchange ex = mockServer.createExchange("GET", "/path/doc/1");
      mockServer.handle(ex);
      assertEquals("1", new String(ex.getResponseBytes(), charset));
      ex = mockServer.createExchange("GET", "/path2/doc/2");
      mockServer.handle(ex);
      assertEquals("2", new String(ex.getResponseBytes(), charset));
    } finally {
      gsa2.stop(0, TimeUnit.SECONDS);
      gsa2.teardown();
      // gsa.stop() is called in @After, so no need for second finally for
      // shutting 'gsa' down.
    }
  }

  @Test
  public void testCreateTransformPipeline() {
    List<Map<String, String>> config = new ArrayList<Map<String, String>>();
    {
      Map<String, String> map = new HashMap<String, String>();
      map.put("name", "testing");
      map.put("factoryMethod", getClass().getName() + ".factoryMethod");
      config.add(map);
    }
    TransformPipeline pipeline
        = GsaCommunicationHandler.createTransformPipeline(config);
    assertEquals(1, pipeline.getDocumentTransforms().size());
    assertEquals(IdentityTransform.class, 
        pipeline.getDocumentTransforms().get(0).getClass());
    assertEquals("testing", pipeline.getNames().get(0));
  }

  @Test
  public void testCreateTransformPipelineEmpty() {
    assertNull(GsaCommunicationHandler.createTransformPipeline(
        Collections.<Map<String, String>>emptyList()));
  }

  @Test
  public void testCreateTransformPipelineNoClassSpecified() {
    List<Map<String, String>> config = new ArrayList<Map<String, String>>();
    {
      Map<String, String> map = new HashMap<String, String>();
      map.put("name", "testing");
      config.add(map);
    }
    thrown.expect(RuntimeException.class);
    TransformPipeline pipeline
        = GsaCommunicationHandler.createTransformPipeline(config);
  }

  @Test
  public void testCreateTransformPipelineMissingClass() {
    List<Map<String, String>> config = new ArrayList<Map<String, String>>();
    {
      Map<String, String> map = new HashMap<String, String>();
      map.put("name", "testing");
      map.put("factoryMethod", "adaptorlib.NotARealClass.fakeMethod");
      config.add(map);
    }
    thrown.expect(RuntimeException.class);
    TransformPipeline pipeline
        = GsaCommunicationHandler.createTransformPipeline(config);
  }

  @Test
  public void testCreateTransformPipelineNoGoodConstructor() throws Exception {
    List<Map<String, String>> config = new ArrayList<Map<String, String>>();
    {
      Map<String, String> map = new HashMap<String, String>();
      map.put("name", "testing");
      map.put("factoryMethod", getClass().getName() + ".wrongFactoryMethod");
      config.add(map);
    }
    thrown.expect(RuntimeException.class);
    TransformPipeline pipeline
        = GsaCommunicationHandler.createTransformPipeline(config);
  }

  @Test
  public void testCreateTransformPipelineConstructorFails() throws Exception {
    List<Map<String, String>> config = new ArrayList<Map<String, String>>();
    {
      Map<String, String> map = new HashMap<String, String>();
      map.put("name", "testing");
      map.put("factoryMethod",
          getClass().getName() + ".cantInstantiateFactoryMethod");
      config.add(map);
    }
    thrown.expect(RuntimeException.class);
    TransformPipeline pipeline
        = GsaCommunicationHandler.createTransformPipeline(config);
  }

  @Test
  public void testCreateTransformPipelineWrongType() throws Exception {
    List<Map<String, String>> config = new ArrayList<Map<String, String>>();
    {
      Map<String, String> map = new HashMap<String, String>();
      map.put("name", "testing");
      map.put("factoryMethod",
          getClass().getName() + ".wrongTypeFactoryMethod");
      config.add(map);
    }
    thrown.expect(ClassCastException.class);
    TransformPipeline pipeline
        = GsaCommunicationHandler.createTransformPipeline(config);
  }

  @Test
  public void testCreateTransformPipelineNoMethod() throws Exception {
    List<Map<String, String>> config = new ArrayList<Map<String, String>>();
    {
      Map<String, String> map = new HashMap<String, String>();
      map.put("name", "testing");
      map.put("factoryMethod", "noFactoryMethodToBeFound");
      config.add(map);
    }
    thrown.expect(RuntimeException.class);
    TransformPipeline pipeline
        = GsaCommunicationHandler.createTransformPipeline(config);
  }

  @Test
  public void testCreateAclTransformNone() throws Exception {
    Map<String, String> config = new HashMap<String, String>();
    assertEquals(new AclTransform(Arrays.<AclTransform.Rule>asList()),
        GsaCommunicationHandler.createAclTransform(config));
  }

  @Test
  public void testCreateAclTransformNoOp() throws Exception {
    Map<String, String> config = new HashMap<String, String>() {
      {
        put("nogood", "name=u1;name=u1");
        put("1", " type=group; ");
        put("3", "nosemicolon");
        put("5", "name=u1; name=u2, namespace=ns1");
        put("6", "type=user, domain=d1;domain=d2");
        put("7", "unknown=key;name=u1");
        put("8", "missingequals;name=u1");
        put("9", "type=unknown;name=u1");
        put("10", "name=u1;type=group");
      }
    };
    assertEquals(new AclTransform(Arrays.asList(
          new AclTransform.Rule(
            new AclTransform.MatchData(true, null, null, null),
            new AclTransform.MatchData(null, null, null, null)),
          new AclTransform.Rule(
            new AclTransform.MatchData(null, "u1", null, null),
            new AclTransform.MatchData(null, "u2", null, "ns1")),
          new AclTransform.Rule(
            new AclTransform.MatchData(false, null, "d1", null),
            new AclTransform.MatchData(null, null, "d2", null)))),
        GsaCommunicationHandler.createAclTransform(config));
  }

  @Test
  public void testKeyStore() throws Exception {
    assertNotNull(GsaCommunicationHandler.getKeyPair("notadaptor",
        KEYSTORE_VALID_FILENAME, "JKS", "notchangeit"));
  }

  @Test
  public void testKeyStoreInvalidType() throws Exception {
    thrown.expect(RuntimeException.class);
    GsaCommunicationHandler.getKeyPair("notadaptor", KEYSTORE_VALID_FILENAME,
        "WRONG", "notchangeit");
  }

  @Test
  public void testKeyStoreMissing() throws Exception {
    thrown.expect(java.io.FileNotFoundException.class);
    GsaCommunicationHandler.getKeyPair("notadaptor", "notarealfile.jks", "JKS",
        "notchangeit");
  }

  @Test
  public void testKeyStoreNoAlias() throws Exception {
    thrown.expect(RuntimeException.class);
    GsaCommunicationHandler.getKeyPair("notherealalias",
        KEYSTORE_VALID_FILENAME, "JKS", "notchangeit");
  }

  private static class NullAdaptor extends AbstractAdaptor {
    private boolean inited;

    @Override
    public void init(AdaptorContext context) {
      inited = true;
    }

    @Override
    public void destroy() {
      inited = false;
    }

    @Override
    public void getDocIds(DocIdPusher pusher) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void getDocContent(Request req, Response resp) throws IOException {
      throw new UnsupportedOperationException();
    }
  }

  static class IdentityTransform implements DocumentTransform {
    @Override
    public void transform(Metadata metadata, Map<String, String> params) {
    }
  }

  public static IdentityTransform factoryMethod(Map<String, String> config) {
    return new IdentityTransform();
  }

  public static IdentityTransform wrongFactoryMethod() {
    return factoryMethod(Collections.<String, String>emptyMap());
  }

  public static IdentityTransform cantInstantiateFactoryMethod(
      Map<String, String> config) {
    throw new RuntimeException("This always seems to happen");
  }

  public static Object wrongTypeFactoryMethod(Map<String, String> config) {
    return new Object();
  }
}
