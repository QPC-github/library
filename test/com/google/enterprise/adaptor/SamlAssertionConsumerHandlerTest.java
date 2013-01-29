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

import com.google.enterprise.secmgr.http.HttpClientInterface;
import com.google.enterprise.secmgr.modules.SamlClient;

import com.sun.net.httpserver.HttpExchange;

import org.junit.*;

import org.opensaml.common.xml.SAMLConstants;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Set;

/**
 * Test cases for {@link SamlAssertionConsumerHandler}.
 */
public class SamlAssertionConsumerHandlerTest {
  private Charset charset = Charset.forName("UTF-8");
  private SessionManager<HttpExchange> sessionManager
      = new SessionManager<HttpExchange>(new MockTimeProvider(),
          new SessionManager.HttpExchangeClientStore(), 1000, 1000);
  private SamlMetadata metadata = new SamlMetadata("localhost", 80,
      "thegsa");
  private SamlAssertionConsumerHandler handler
      = new SamlAssertionConsumerHandler(sessionManager);
  private MockHttpExchange ex
      = new MockHttpExchange("GET", "/?SAMLart=1234someid5678",
          new MockHttpContext(null, "/"));
  private MockHttpExchange initialEx
      = new MockHttpExchange("GET", "/doc/someid",
          new MockHttpContext(null, "/doc/"));

  private static final String GOLDEN_ARTIFACT_RESOLVE_REQUEST
      = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
      + "<soap11:Envelope "
      +   "xmlns:soap11=\"http://schemas.xmlsoap.org/soap/envelope/\">"
      +   "<soap11:Body>"
      +     "<saml2p:ArtifactResolve "
      +       "ID=\"someid\" "
      +       "IssueInstant=\"sometime\" "
      +       "Version=\"2.0\" "
      +       "xmlns:saml2p=\"urn:oasis:names:tc:SAML:2.0:protocol\">"
      +       "<saml2:Issuer "
      +         "xmlns:saml2=\"urn:oasis:names:tc:SAML:2.0:assertion\">"
      +         "http://google.com/enterprise/gsa/adaptor"
      +       "</saml2:Issuer>"
      +       "<saml2p:Artifact>"
      +         "1234someid5678"
      +       "</saml2p:Artifact>"
      +     "</saml2p:ArtifactResolve>"
      +   "</soap11:Body>"
      + "</soap11:Envelope>";


  @BeforeClass
  public static void initSaml() {
    GsaCommunicationHandler.bootstrapOpenSaml();
  }

  @Test
  public void testNormal() throws Exception {
    SamlHttpClient httpClient = new SamlHttpClient() {
      @Override
      protected void handleExchange(ClientExchange ex) {
        String body = new String(ex.getRequestBody(), charset);
        body = massageMessage(body);
        assertEquals(GOLDEN_ARTIFACT_RESOLVE_REQUEST, body);

        // Generate valid response.
        String issuer = metadata.getPeerEntity().getEntityID();
        String recipient = metadata.getLocalEntity()
            .getSPSSODescriptor(SAMLConstants.SAML20P_NS)
            .getAssertionConsumerServices().get(0).getLocation();
        String audience = metadata.getLocalEntity().getEntityID();
        String response
            = "<SOAP-ENV:Envelope "
            +   "xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            +   "<SOAP-ENV:Body>"
            +     "<samlp:ArtifactResponse "
            +       "xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\" "
            +       "xmlns=\"urn:oasis:names:tc:SAML:2.0:assertion\" "
            +       "ID=\"someid1\" Version=\"2.0\" "
            +       "InResponseTo=\"" + samlClient.getRequestId() + "\" "
            +       "IssueInstant=\"2010-01-01T01:01:01Z\">"
            +       "<Issuer>" + issuer + "</Issuer>"
            +       "<samlp:Status>"
            +         "<samlp:StatusCode "
            +           "Value=\"urn:oasis:names:tc:SAML:2.0:status:Success\"/>"
            +       "</samlp:Status>"
            +       "<samlp:Response "
            +         "ID=\"someid2\" "
            +         "Version=\"2.0\" "
            +         "IssueInstant=\"2010-01-01T01:01:01Z\">"
            +         "<samlp:Status>"
            +           "<samlp:StatusCode "
            +             "Value=\"urn:oasis:names:tc:SAML:2.0:status:Success\"/>"
            +         "</samlp:Status>"
            +         "<Assertion "
            +           "Version=\"2.0\" "
            +           "ID=\"someid3\" "
            +           "IssueInstant=\"2010-01-01T01:01:01Z\">"
            +           "<Issuer>" + issuer + "</Issuer>"
            +           "<Subject>"
            +             "<NameID>CN=Polly Hedra</NameID>"
            +             "<SubjectConfirmation "
            +               "Method=\"urn:oasis:names:tc:SAML:2.0:cm:bearer\">"
            +               "<SubjectConfirmationData "
            +                 "InResponseTo=\"" + samlClient.getRequestId() + "\" "
            +                 "Recipient=\"" + recipient + "\" "
            +                 "NotOnOrAfter=\"2030-01-01T01:01:01Z\"/>"
            +             "</SubjectConfirmation>"
            +           "</Subject>"
            +           "<Conditions "
            +             "NotBefore=\"2010-01-01T01:01:01Z\">"
            +             "<AudienceRestriction>"
            +               "<Audience>" + audience + "</Audience>"
            +             "</AudienceRestriction>"
            +           "</Conditions>"
            +           "<AuthnStatement "
            +             "AuthnInstant=\"2010-01-01T01:01:01Z\"/>"
            +         "</Assertion>"
            +       "</samlp:Response>"
            +     "</samlp:ArtifactResponse>"
            +   "</SOAP-ENV:Body>"
            + "</SOAP-ENV:Envelope>";
        ex.setStatusCode(200);
        ex.setResponseStream(response.getBytes(charset));
      }
    };
    SamlClient samlClient = createSamlClient(httpClient);
    httpClient.setSamlClient(samlClient);
    issueRequest(ex, initialEx, samlClient);

    handler.handle(ex);
    assertEquals(303, ex.getResponseCode());
    assertTrue(isAuthned(sessionManager.getSession(ex)));
    AuthnState authnState = (AuthnState) sessionManager.getSession(ex)
        .getAttribute(AuthnState.SESSION_ATTR_NAME);
    AuthnIdentity identity = authnState.getIdentity();
    assertEquals("CN=Polly Hedra", identity.getUsername());
    assertNull(identity.getGroups());
    assertNull(identity.getPassword());
  }

  @Test
  public void testNormalWithExtension() throws Exception {
    SamlHttpClient httpClient = new SamlHttpClient() {
      @Override
      protected void handleExchange(ClientExchange ex) {
        String body = new String(ex.getRequestBody(), charset);
        body = massageMessage(body);
        assertEquals(GOLDEN_ARTIFACT_RESOLVE_REQUEST, body);

        // Generate valid response.
        String issuer = metadata.getPeerEntity().getEntityID();
        String recipient = metadata.getLocalEntity()
            .getSPSSODescriptor(SAMLConstants.SAML20P_NS)
            .getAssertionConsumerServices().get(0).getLocation();
        String audience = metadata.getLocalEntity().getEntityID();
        String response
            = "<SOAP-ENV:Envelope "
            +   "xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            +   "<SOAP-ENV:Body>"
            +     "<samlp:ArtifactResponse "
            +       "xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\" "
            +       "xmlns=\"urn:oasis:names:tc:SAML:2.0:assertion\" "
            +       "ID=\"someid1\" Version=\"2.0\" "
            +       "InResponseTo=\"" + samlClient.getRequestId() + "\" "
            +       "IssueInstant=\"2010-01-01T01:01:01Z\">"
            +       "<Issuer>" + issuer + "</Issuer>"
            +       "<samlp:Status>"
            +         "<samlp:StatusCode "
            +           "Value=\"urn:oasis:names:tc:SAML:2.0:status:Success\"/>"
            +       "</samlp:Status>"
            +       "<samlp:Response "
            +         "ID=\"someid2\" "
            +         "Version=\"2.0\" "
            +         "IssueInstant=\"2010-01-01T01:01:01Z\">"
            +         "<samlp:Status>"
            +           "<samlp:StatusCode "
            +             "Value=\"urn:oasis:names:tc:SAML:2.0:status:Success\"/>"
            +         "</samlp:Status>"
            +         "<Assertion "
            +           "Version=\"2.0\" "
            +           "ID=\"someid3\" "
            +           "IssueInstant=\"2010-01-01T01:01:01Z\">"
            +           "<Issuer>" + issuer + "</Issuer>"
            +           "<Subject>"
            +             "<NameID>CN=Polly Hedra</NameID>"
            +             "<SubjectConfirmation "
            +               "Method=\"urn:oasis:names:tc:SAML:2.0:cm:bearer\">"
            +               "<SubjectConfirmationData "
            +                 "InResponseTo=\"" + samlClient.getRequestId() + "\" "
            +                 "Recipient=\"" + recipient + "\" "
            +                 "NotOnOrAfter=\"2030-01-01T01:01:01Z\"/>"
            +             "</SubjectConfirmation>"
            +           "</Subject>"
            +           "<Conditions "
            +             "NotBefore=\"2010-01-01T01:01:01Z\">"
            +             "<AudienceRestriction>"
            +               "<Audience>" + audience + "</Audience>"
            +             "</AudienceRestriction>"
            +           "</Conditions>"
            +           "<AuthnStatement "
            +             "AuthnInstant=\"2010-01-01T01:01:01Z\">"
            +             "<AuthnContext>"
            +               "<AuthnContextClassRef>"
            +                 "urn:oasis:names:tc:SAML:2.0:ac:classes:InternetProtocolPassword"
            +               "</AuthnContextClassRef>"
            +             "</AuthnContext>"
            +           "</AuthnStatement>"
            +           "<AttributeStatement>"
            +             "<Attribute Name=\"SecurityManagerState\">"
            +               "<AttributeValue>"
            + "{"
            +   "\"version\": 1,"
            +   "\"timeStamp\": 1330042321589,"
            +   "\"sessionState\": {"
            +     "\"instructions\": ["
            +       "{"
            +         "\"operation\": \"ADD_CREDENTIAL\","
            +         "\"authority\": "
            + "\"http://google.com/enterprise/gsa/security-manager/Default\","
            +         "\"operand\": {"
            +           "\"name\": \"CN=Polly Hedra\","
            +           "\"typeName\": \"AuthnPrincipal\""
            +         "}"
            +       "},"
            +       "{"
            +         "\"operation\": \"ADD_CREDENTIAL\","
            +         "\"authority\": "
            + "\"http://google.com/enterprise/gsa/security-manager/Default\","
            +         "\"operand\": {"
            +           "\"password\": \"p0ck3t\","
            +           "\"typeName\": \"CredPassword\""
            +         "}"
            +       "},"
            +       "{"
            +         "\"operation\": \"ADD_VERIFICATION\","
            +         "\"authority\": "
            + "\"http://google.com/enterprise/gsa/security-manager/adaptor\","
            +         "\"operand\": {"
            +           "\"status\": \"VERIFIED\","
            +           "\"expirationTime\": 1330043521581,"
            +           "\"credentials\": ["
            +             "{"
            +               "\"name\": \"CN=Polly Hedra\","
            +               "\"typeName\": \"AuthnPrincipal\""
            +             "},"
            +             "{"
            +               "\"password\": \"p0ck3t\","
            +               "\"typeName\": \"CredPassword\""
            +             "}"
            +           "]"
            +         "}"
            +       "}"
            +     "]"
            +   "},"
            +   "\"pviCredentials\": {"
            +     "\"username\": \"CN=Polly Hedra\","
            +     "\"password\": \"p0ck3t\","
            +     "\"groups\": [\"group1\", \"pollysGroup\"]"
            +   "},"
            +   "\"basicCredentials\": {"
            +     "\"username\": \"CN=Polly Hedra\","
            +     "\"password\": \"p0ck3t\","
            +     "\"groups\": [\"group1\", \"pollysGroup\"]"
            +   "},"
            +   "\"verifiedCredentials\": ["
            +     "{"
            +       "\"username\": \"CN=Polly Hedra\","
            +       "\"password\": \"p0ck3t\","
            +       "\"groups\": [\"group1\", \"pollysGroup\"]"
            +     "}"
            +   "],"
            +   "\"connectorCredentials\": [],"
            +   "\"cookies\": []"
            + "}"
            +               "</AttributeValue>"
            +             "</Attribute>"
            +           "</AttributeStatement>"
            +         "</Assertion>"
            +       "</samlp:Response>"
            +     "</samlp:ArtifactResponse>"
            +   "</SOAP-ENV:Body>"
            + "</SOAP-ENV:Envelope>";
        ex.setStatusCode(200);
        ex.setResponseStream(response.getBytes(charset));
      }
    };
    SamlClient samlClient = createSamlClient(httpClient);
    httpClient.setSamlClient(samlClient);
    issueRequest(ex, initialEx, samlClient);

    handler.handle(ex);
    assertEquals(303, ex.getResponseCode());
    assertTrue(isAuthned(sessionManager.getSession(ex)));
    AuthnState authnState = (AuthnState) sessionManager.getSession(ex)
        .getAttribute(AuthnState.SESSION_ATTR_NAME);
    AuthnIdentity identity = authnState.getIdentity();
    assertEquals("CN=Polly Hedra", identity.getUsername());
    // Make sure that the information from the extensions was parsed out and
    // made available for later use.
    Set<String> groups = new HashSet<String>();
    groups.add("group1");
    groups.add("pollysGroup");
    assertEquals(groups, identity.getGroups());
    assertEquals("p0ck3t", identity.getPassword());
  }

  @Test
  public void testAuthnFailure() throws Exception {
    SamlHttpClient httpClient = new SamlHttpClient() {
      @Override
      protected void handleExchange(ClientExchange ex) {
        String body = new String(ex.getRequestBody(), charset);
        body = massageMessage(body);
        assertEquals(GOLDEN_ARTIFACT_RESOLVE_REQUEST, body);

        // Generate valid response for authn failed.
        String issuer = metadata.getPeerEntity().getEntityID();
        String response
            = "<SOAP-ENV:Envelope "
            +   "xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            +   "<SOAP-ENV:Body>"
            +     "<samlp:ArtifactResponse "
            +       "xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\" "
            +       "xmlns=\"urn:oasis:names:tc:SAML:2.0:assertion\" "
            +       "ID=\"someid1\" Version=\"2.0\" "
            +       "InResponseTo=\"" + samlClient.getRequestId() + "\" "
            +       "IssueInstant=\"2010-01-01T01:01:01Z\">"
            +       "<Issuer>" + issuer + "</Issuer>"
            +       "<samlp:Status>"
            +         "<samlp:StatusCode "
            +           "Value=\"urn:oasis:names:tc:SAML:2.0:status:Success\"/>"
            +       "</samlp:Status>"
            +       "<samlp:Response "
            +         "ID=\"someid2\" "
            +         "Version=\"2.0\" "
            +         "IssueInstant=\"2010-01-01T01:01:01Z\">"
            +         "<samlp:Status>"
            +           "<samlp:StatusCode "
            +             "Value=\"urn:oasis:names:tc:SAML:2.0:status:AuthnFailed\"/>"
            +         "</samlp:Status>"
            +       "</samlp:Response>"
            +     "</samlp:ArtifactResponse>"
            +   "</SOAP-ENV:Body>"
            + "</SOAP-ENV:Envelope>";
        ex.setStatusCode(200);
        ex.setResponseStream(response.getBytes(charset));
      }
    };
    SamlClient samlClient = createSamlClient(httpClient);
    httpClient.setSamlClient(samlClient);
    issueRequest(ex, initialEx, samlClient);

    handler.handle(ex);
    assertEquals(403, ex.getResponseCode());
    assertTrue(!isAuthned(sessionManager.getSession(ex)));
  }

  @Test
  public void testNoAuthnResponse() throws Exception {
    SamlHttpClient httpClient = new SamlHttpClient() {
      @Override
      protected void handleExchange(ClientExchange ex) {
        String body = new String(ex.getRequestBody(), charset);
        body = massageMessage(body);
        assertEquals(GOLDEN_ARTIFACT_RESOLVE_REQUEST, body);

        // Generate valid response for artifact requesting failure.
        String issuer = metadata.getPeerEntity().getEntityID();
        String response
            = "<SOAP-ENV:Envelope "
            +   "xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            +   "<SOAP-ENV:Body>"
            +     "<samlp:ArtifactResponse "
            +       "xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\" "
            +       "xmlns=\"urn:oasis:names:tc:SAML:2.0:assertion\" "
            +       "ID=\"someid1\" Version=\"2.0\" "
            +       "InResponseTo=\"" + samlClient.getRequestId() + "\" "
            +       "IssueInstant=\"2010-01-01T01:01:01Z\">"
            +       "<Issuer>" + issuer + "</Issuer>"
            +       "<samlp:Status>"
            +         "<samlp:StatusCode "
            +           "Value=\"urn:oasis:names:tc:SAML:2.0:status:Requester\"/>"
            +       "</samlp:Status>"
            +     "</samlp:ArtifactResponse>"
            +   "</SOAP-ENV:Body>"
            + "</SOAP-ENV:Envelope>";
        ex.setStatusCode(200);
        ex.setResponseStream(response.getBytes(charset));
      }
    };
    SamlClient samlClient = createSamlClient(httpClient);
    httpClient.setSamlClient(samlClient);
    issueRequest(ex, initialEx, samlClient);

    handler.handle(ex);
    assertEquals(403, ex.getResponseCode());
    assertTrue(!isAuthned(sessionManager.getSession(ex)));
  }

  @Test
  public void testWrongResponse() throws Exception {
    SamlHttpClient httpClient = new SamlHttpClient() {
      @Override
      protected void handleExchange(ClientExchange ex) {
        String body = new String(ex.getRequestBody(), charset);
        body = massageMessage(body);
        assertEquals(GOLDEN_ARTIFACT_RESOLVE_REQUEST, body);

        // Generate valid response for successful authn, but for the wrong
        // request.
        String issuer = metadata.getPeerEntity().getEntityID();
        String recipient = metadata.getLocalEntity()
            .getSPSSODescriptor(SAMLConstants.SAML20P_NS)
            .getAssertionConsumerServices().get(0).getLocation();
        String audience = metadata.getLocalEntity().getEntityID();
        String response
            = "<SOAP-ENV:Envelope "
            +   "xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            +   "<SOAP-ENV:Body>"
            +     "<samlp:ArtifactResponse "
            +       "xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\" "
            +       "xmlns=\"urn:oasis:names:tc:SAML:2.0:assertion\" "
            +       "ID=\"someid1\" Version=\"2.0\" "
            +       "InResponseTo=\"" + samlClient.getRequestId() + "\" "
            +       "IssueInstant=\"2010-01-01T01:01:01Z\">"
            +       "<Issuer>" + issuer + "</Issuer>"
            +       "<samlp:Status>"
            +         "<samlp:StatusCode "
            +           "Value=\"urn:oasis:names:tc:SAML:2.0:status:Success\"/>"
            +       "</samlp:Status>"
            +       "<samlp:Response "
            +         "ID=\"someid2\" "
            +         "Version=\"2.0\" "
            +         "IssueInstant=\"2010-01-01T01:01:01Z\">"
            +         "<samlp:Status>"
            +           "<samlp:StatusCode "
            +             "Value=\"urn:oasis:names:tc:SAML:2.0:status:Success\"/>"
            +         "</samlp:Status>"
            +         "<Assertion "
            +           "Version=\"2.0\" "
            +           "ID=\"someid3\" "
            +           "IssueInstant=\"2010-01-01T01:01:01Z\">"
            +           "<Issuer>" + issuer + "</Issuer>"
            +           "<Subject>"
            +             "<NameID>CN=Polly Hedra</NameID>"
            +             "<SubjectConfirmation "
            +               "Method=\"urn:oasis:names:tc:SAML:2.0:cm:bearer\">"
            +               "<SubjectConfirmationData "
            +                 "InResponseTo=\"notthis" + samlClient.getRequestId() + "\" "
            +                 "Recipient=\"" + recipient + "\" "
            +                 "NotOnOrAfter=\"2030-01-01T01:01:01Z\"/>"
            +             "</SubjectConfirmation>"
            +           "</Subject>"
            +           "<Conditions "
            +             "NotBefore=\"2010-01-01T01:01:01Z\">"
            +             "<AudienceRestriction>"
            +               "<Audience>" + audience + "</Audience>"
            +             "</AudienceRestriction>"
            +           "</Conditions>"
            +           "<AuthnStatement "
            +             "AuthnInstant=\"2010-01-01T01:01:01Z\"/>"
            +         "</Assertion>"
            +       "</samlp:Response>"
            +     "</samlp:ArtifactResponse>"
            +   "</SOAP-ENV:Body>"
            + "</SOAP-ENV:Envelope>";
        ex.setStatusCode(200);
        ex.setResponseStream(response.getBytes(charset));
      }
    };
    SamlClient samlClient = createSamlClient(httpClient);
    httpClient.setSamlClient(samlClient);
    issueRequest(ex, initialEx, samlClient);

    handler.handle(ex);
    assertEquals(403, ex.getResponseCode());
    assertTrue(!isAuthned(sessionManager.getSession(ex)));
  }

  @Test
  public void testWrongIssuer() throws Exception {
    SamlHttpClient httpClient = new SamlHttpClient() {
      @Override
      protected void handleExchange(ClientExchange ex) {
        String body = new String(ex.getRequestBody(), charset);
        body = massageMessage(body);
        assertEquals(GOLDEN_ARTIFACT_RESOLVE_REQUEST, body);

        // Generate valid response, but from wrong issuer.
        String issuer = metadata.getPeerEntity().getEntityID();
        String recipient = metadata.getLocalEntity()
            .getSPSSODescriptor(SAMLConstants.SAML20P_NS)
            .getAssertionConsumerServices().get(0).getLocation();
        String audience = metadata.getLocalEntity().getEntityID();
        String response
            = "<SOAP-ENV:Envelope "
            +   "xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            +   "<SOAP-ENV:Body>"
            +     "<samlp:ArtifactResponse "
            +       "xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\" "
            +       "xmlns=\"urn:oasis:names:tc:SAML:2.0:assertion\" "
            +       "ID=\"someid1\" Version=\"2.0\" "
            +       "InResponseTo=\"" + samlClient.getRequestId() + "\" "
            +       "IssueInstant=\"2010-01-01T01:01:01Z\">"
            +       "<Issuer>" + issuer + "</Issuer>"
            +       "<samlp:Status>"
            +         "<samlp:StatusCode "
            +           "Value=\"urn:oasis:names:tc:SAML:2.0:status:Success\"/>"
            +       "</samlp:Status>"
            +       "<samlp:Response "
            +         "ID=\"someid2\" "
            +         "Version=\"2.0\" "
            +         "IssueInstant=\"2010-01-01T01:01:01Z\">"
            // Add an issuer that is not the expected one.
            +         "<Issuer>notthexpected" + issuer + "</Issuer>"
            +         "<samlp:Status>"
            +           "<samlp:StatusCode "
            +             "Value=\"urn:oasis:names:tc:SAML:2.0:status:Success\"/>"
            +         "</samlp:Status>"
            +         "<Assertion "
            +           "Version=\"2.0\" "
            +           "ID=\"someid3\" "
            +           "IssueInstant=\"2010-01-01T01:01:01Z\">"
            +           "<Issuer>" + issuer + "</Issuer>"
            +           "<Subject>"
            +             "<NameID>CN=Polly Hedra</NameID>"
            +             "<SubjectConfirmation "
            +               "Method=\"urn:oasis:names:tc:SAML:2.0:cm:bearer\">"
            +               "<SubjectConfirmationData "
            +                 "InResponseTo=\"" + samlClient.getRequestId() + "\" "
            +                 "Recipient=\"" + recipient + "\" "
            +                 "NotOnOrAfter=\"2030-01-01T01:01:01Z\"/>"
            +             "</SubjectConfirmation>"
            +           "</Subject>"
            +           "<Conditions "
            +             "NotBefore=\"2010-01-01T01:01:01Z\">"
            +             "<AudienceRestriction>"
            +               "<Audience>" + audience + "</Audience>"
            +             "</AudienceRestriction>"
            +           "</Conditions>"
            +           "<AuthnStatement "
            +             "AuthnInstant=\"2010-01-01T01:01:01Z\"/>"
            +         "</Assertion>"
            +       "</samlp:Response>"
            +     "</samlp:ArtifactResponse>"
            +   "</SOAP-ENV:Body>"
            + "</SOAP-ENV:Envelope>";
        ex.setStatusCode(200);
        ex.setResponseStream(response.getBytes(charset));
      }
    };
    SamlClient samlClient = createSamlClient(httpClient);
    httpClient.setSamlClient(samlClient);
    issueRequest(ex, initialEx, samlClient);

    handler.handle(ex);
    assertEquals(403, ex.getResponseCode());
    assertTrue(!isAuthned(sessionManager.getSession(ex)));
  }

  @Test
  public void testAlreadyAuthned() throws Exception {
    MockHttpClient httpClient = new MockHttpClient() {
      @Override
      protected void handleExchange(ClientExchange ex) {
        fail("No request should have been issued.");
      }
    };
    SamlClient samlClient = createSamlClient(httpClient);
    issueRequest(ex, initialEx, samlClient);

    // Authenticate the session.
    Session session = sessionManager.getSession(ex, false);
    AuthnState authn = (AuthnState) session
        .getAttribute(AuthnState.SESSION_ATTR_NAME);
    AuthnIdentity identity = new AuthnIdentityImpl.Builder("test").build();
    authn.authenticated(identity, Long.MAX_VALUE);

    handler.handle(ex);
    assertEquals(409, ex.getResponseCode());
    assertTrue(isAuthned(sessionManager.getSession(ex)));
  }

  @Test
  public void testNoSession() throws Exception {
    handler.handle(ex);
    assertEquals(409, ex.getResponseCode());
    assertTrue(!isAuthned(sessionManager.getSession(ex)));
  }

  @Test
  public void testUnrequestedAuthnResponse() throws Exception {
    AuthnState authnState = new AuthnState();
    sessionManager.getSession(ex).setAttribute(AuthnState.SESSION_ATTR_NAME,
                                               authnState);

    handler.handle(ex);
    assertEquals(500, ex.getResponseCode());
    assertTrue(!isAuthned(sessionManager.getSession(ex)));
  }

  @Test
  public void testPost() throws Exception {
    MockHttpExchange ex
        = new MockHttpExchange("POST", "/?SAMLart=1234someid5678",
                               new MockHttpContext(null, "/"));
    handler.handle(ex);
    assertEquals(405, ex.getResponseCode());
    assertTrue(!isAuthned(sessionManager.getSession(ex)));
  }

  private SamlClient createSamlClient(HttpClientInterface httpClient) {
    return new SamlClient(metadata.getLocalEntity(), metadata.getPeerEntity(),
                          "Testing", null, httpClient);
  }

  private void issueRequest(HttpExchange ex, HttpExchange initialEx,
                            SamlClient samlClient) throws IOException {
    AuthnState authnState = new AuthnState();
    authnState.startAttempt(samlClient, ex.getRequestURI());
    sessionManager.getSession(ex).setAttribute(AuthnState.SESSION_ATTR_NAME,
                                               authnState);
    // Used to generate a request id.
    samlClient.sendAuthnRequest(new HttpExchangeOutTransportAdapter(initialEx));
  }

  private boolean isAuthned(Session session) {
    AuthnState authnState = (AuthnState) session
        .getAttribute(AuthnState.SESSION_ATTR_NAME);
    if (authnState == null) {
      return false;
    }
    return authnState.isAuthenticated();
  }

  private String massageMessage(String message) {
    return message.replaceAll("ID=\"[^\"]+\"", "ID=\"someid\"")
        .replaceAll("IssueInstant=\"[^\"]+\"", "IssueInstant=\"sometime\"");
  }

  private abstract static class SamlHttpClient extends MockHttpClient {
    protected SamlClient samlClient;

    public void setSamlClient(SamlClient samlClient) {
      this.samlClient = samlClient;
    }
  }
}
