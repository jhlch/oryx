/*
 * Copyright (c) 2014, Cloudera, Inc. and Intel Corp. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */

package com.cloudera.oryx.lambda.serving;

import com.cloudera.oryx.common.io.IOUtils;
import com.cloudera.oryx.common.settings.ConfigUtils;
import com.google.common.base.Preconditions;
import com.typesafe.config.Config;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Server;
import org.apache.catalina.Wrapper;
import org.apache.catalina.authenticator.DigestAuthenticator;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.JreMemoryLeakPreventionListener;
import org.apache.catalina.core.ThreadLocalLeakPreventionListener;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.LoginConfig;
import org.apache.tomcat.util.descriptor.web.SecurityCollection;
import org.apache.tomcat.util.descriptor.web.SecurityConstraint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import javax.net.ssl.SSLContext;

public final class ServingLayer implements Closeable {

  private static final Logger log = LoggerFactory.getLogger(ServingLayer.class);

  private final Config config;
  private final int port;
  private final int securePort;
  private final String userName;
  private final String password;
  private final Path keystoreFile;
  private final String keystorePassword;
  private final String contextPathURIBase;
  private final String appResourcesPackages;
  private Tomcat tomcat;
  private Context context;
  private Path noSuchBaseDir;

  /**
   * Creates a new instance with the given configuration.
   *
   * @param config configuration for the serving layer
   */
  public ServingLayer(Config config) {
    this.config = config;
    this.port = config.getInt("serving.api.port");
    this.securePort = config.getInt("serving.api.secure-port");
    this.userName = ConfigUtils.getOptionalString(config, "serving.api.user-name");
    this.password = ConfigUtils.getOptionalString(config, "serving.api.password");
    String keystoreFileString =
        ConfigUtils.getOptionalString(config, "serving.api.password");
    this.keystoreFile = keystoreFileString == null ? null : Paths.get(keystoreFileString);
    this.keystorePassword =
        ConfigUtils.getOptionalString(config, "serving.api.keystore-password");
    String contextPathString = config.getString("serving.api.context-path");
    if (contextPathString == null ||
        contextPathString.isEmpty() ||
        "/".equals(contextPathString)) {
      contextPathString = "";
    }
    this.contextPathURIBase = contextPathString;
    this.appResourcesPackages = config.getString("serving.application-resources");
  }

  public synchronized void start() throws IOException {
    Preconditions.checkState(tomcat == null);
    // Has to happen very early before Tomcat init:
    System.setProperty("org.apache.tomcat.util.buf.UDecoder.ALLOW_ENCODED_SLASH", "true");
    noSuchBaseDir = Files.createTempDirectory("Oryx");
    noSuchBaseDir.toFile().deleteOnExit();

    Tomcat tomcat = new Tomcat();
    Connector connector = makeConnector();
    configureTomcat(tomcat, connector);
    configureEngine(tomcat.getEngine());
    configureServer(tomcat.getServer());
    configureHost(tomcat.getHost());
    makeContext(tomcat, noSuchBaseDir);

    try {
      tomcat.start();
    } catch (LifecycleException le) {
      throw new IOException(le);
    }
    this.tomcat = tomcat;
  }

  /**
   * Blocks and waits until the server shuts down.
   */
  public void await() {
    tomcat.getServer().await();
  }

  /**
   * @return Tomcat's internal context. Really only to be used for testing!
   */
  public Context getContext() {
    return context;
  }

  @Override
  public synchronized void close() {
    if (tomcat != null) {
      try {
        tomcat.stop();
        tomcat.destroy();
      } catch (LifecycleException le) {
        log.warn("Unexpected error while stopping", le);
      } finally {
        tomcat = null;
      }
      try {
        IOUtils.deleteRecursively(noSuchBaseDir);
      } catch (IOException e) {
        log.warn("Failed to delete {}", noSuchBaseDir);
      }
    }
  }

  private void configureTomcat(Tomcat tomcat, Connector connector) {
    tomcat.setBaseDir(noSuchBaseDir.toAbsolutePath().toString());
    tomcat.setConnector(connector);
    tomcat.getService().addConnector(connector);
  }

  private void configureEngine(Engine engine) {
    if (userName != null && password != null) {
      InMemoryRealm realm = new InMemoryRealm();
      realm.addUser(userName, password);
      engine.setRealm(realm);
    }
  }

  private static void configureServer(Server server) {
    server.addLifecycleListener(new JreMemoryLeakPreventionListener());
    server.addLifecycleListener(new ThreadLocalLeakPreventionListener());
  }

  private static void configureHost(Host host) {
    host.setAutoDeploy(false);
  }

  private Connector makeConnector() {
    Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");

    if (keystoreFile == null && keystorePassword == null) {
      // HTTP connector
      connector.setPort(port);
      connector.setSecure(false);
      connector.setScheme("http");

    } else {

      // HTTPS connector
      connector.setPort(securePort);
      connector.setSecure(true);
      connector.setScheme("https");
      connector.setAttribute("SSLEnabled", "true");
      String protocol = chooseSSLProtocol("TLSv1.2", "TLSv1.1");
      if (protocol != null) {
        connector.setAttribute("sslProtocol", protocol);
      }
      if (keystoreFile != null) {
        connector.setAttribute("keystoreFile", keystoreFile.toAbsolutePath().toFile());
      }
      connector.setAttribute("keystorePass", keystorePassword);
    }

    // Keep quiet about the server type
    connector.setXpoweredBy(false);
    connector.setAttribute("server", "Oryx");

    // Basic tuning params:
    connector.setAttribute("maxThreads", 400);
    connector.setAttribute("acceptCount", 50);
    //connector.setAttribute("connectionTimeout", 2000);
    connector.setAttribute("maxKeepAliveRequests", 100);

    // Avoid running out of ephemeral ports under heavy load?
    connector.setAttribute("socket.soReuseAddress", true);

    connector.setMaxPostSize(0);
    connector.setAttribute("disableUploadTimeout", false);

    // Allow long URLs
    connector.setAttribute("maxHttpHeaderSize", 65536);

    return connector;
  }

  private static String chooseSSLProtocol(String... protocols) {
    for (String protocol : protocols) {
      try {
        SSLContext.getInstance(protocol);
        return protocol;
      } catch (NoSuchAlgorithmException ignored) {
        log.info("SSL protocol {} is not supported", protocol);
      }
    }
    log.warn("No supported SSL protocols among {}", Arrays.toString(protocols));
    return null;
  }

  private Context makeContext(Tomcat tomcat, Path noSuchBaseDir) throws IOException {
    Path contextPath = noSuchBaseDir.resolve("context");
    Files.createDirectories(contextPath);

    context = tomcat.addContext(contextPathURIBase, contextPath.toAbsolutePath().toString());

    context.setWebappVersion("3.1");
    context.setName("Oryx");

    // OryxApplication only needs one config value, so just pass it
    context.addParameter(OryxApplication.class.getName() + ".packages", appResourcesPackages);
    // ModelManagerListener will need whole config
    String serializedConfig = ConfigUtils.serialize(config);
    context.addParameter(ConfigUtils.class.getName() + ".serialized", serializedConfig);

    Wrapper wrapper =
        Tomcat.addServlet(context, "Jersey", "org.glassfish.jersey.servlet.ServletContainer");
    wrapper.addInitParameter("javax.ws.rs.Application", OryxApplication.class.getName());
    //wrapper.addInitParameter(OryxApplication.class.getName() + ".packages", appResourcesPackage);
    wrapper.addMapping("/*");
    wrapper.setLoadOnStartup(1);

    context.addApplicationListener(ModelManagerListener.class.getName());

    boolean needHTTPS = keystoreFile != null;
    boolean needAuthentication = userName != null;

    if (needHTTPS || needAuthentication) {

      SecurityCollection securityCollection = new SecurityCollection();
      securityCollection.addPattern("/*");
      SecurityConstraint securityConstraint = new SecurityConstraint();
      securityConstraint.addCollection(securityCollection);

      if (needHTTPS) {
        securityConstraint.setUserConstraint("CONFIDENTIAL");
      }

      if (needAuthentication) {

        LoginConfig loginConfig = new LoginConfig();
        loginConfig.setAuthMethod("DIGEST");
        loginConfig.setRealmName(InMemoryRealm.NAME);
        context.setLoginConfig(loginConfig);

        securityConstraint.addAuthRole(InMemoryRealm.AUTH_ROLE);

        context.addSecurityRole(InMemoryRealm.AUTH_ROLE);
        DigestAuthenticator authenticator = new DigestAuthenticator();
        authenticator.setNonceValidity(10 * 1000L); // Shorten from 5 minutes to 10 seconds
        authenticator.setNonceCacheSize(20000); // Increase from 1000 to 20000
        context.getPipeline().addValve(authenticator);
      }

      context.addConstraint(securityConstraint);
    }

    context.setCookies(false);

    return context;
  }
}

