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

package adaptorlib;

import java.io.*;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.*;

/**
 * Configuration values for this program like the GSA's hostname. Also several
 * knobs, or controls, for changing the behavior of the program.
 */
public class Config {
  protected static final String DEFAULT_CONFIG_FILE
      = "adaptor-config.properties";

  private static final Logger log = Logger.getLogger(Config.class.getName());

  /** Configuration keys whose default value is {@code null}. */
  protected final Set<String> noDefaultConfig = new HashSet<String>();
  /** Default configuration values. */
  protected final Properties defaultConfig = new Properties();
  /** Overriding configuration values loaded from command line. */
  // Reads require no additional locks, but modifications require lock on 'this'
  // to prevent lost updates.
  protected volatile Properties config = new Properties(defaultConfig);
  /** Default configuration to use in {@link #loadDefaultConfigFile}. */
  protected File defaultConfigFile = new File(DEFAULT_CONFIG_FILE);
  /**
   * The actual config file in use, or {@code null} if none have been loaded.
   */
  protected File configFile;
  protected long configFileLastModified;
  protected List<ConfigModificationListener> modificationListeners
      = new CopyOnWriteArrayList<ConfigModificationListener>();

  public Config() {
    String hostname = null;
    try {
      hostname = InetAddress.getLocalHost().getCanonicalHostName();
    } catch (UnknownHostException ex) {
      // Ignore
    }
    addKey("server.hostname", hostname);
    addKey("server.port", "5678");
    addKey("server.dashboardPort", "5679");
    addKey("server.docIdPath", "/doc/");
    addKey("server.fullAccessHosts", "");
    addKey("server.secure", "false");
    addKey("server.keyAlias", "adaptor");
    addKey("server.maxWorkerThreads", "16");
    // A queue that takes one second to drain, assuming 16 threads and 100 ms
    // for each request.
    addKey("server.queueCapacity", "160");
    addKey("server.useCompression", "true");
    addKey("gsa.hostname", null);
    addKey("gsa.characterEncoding", "UTF-8");
    addKey("docId.isUrl", "false");
    addKey("feed.name", "testfeed");
    addKey("feed.noRecrawlBitEnabled", "false");
    addKey("feed.crawlImmediatelyBitEnabled", "false");
    //addKey("feed.noFollowBitEnabled", "false");
    addKey("feed.maxUrls", "5000");
    addKey("adaptor.pushDocIdsOnStartup", "true");
    addKey("adaptor.autoUnzip", "false");
    // 3:00 AM every day.
    addKey("adaptor.fullListingSchedule", "0 3 * * *");
    // 15 minutes.
    addKey("adaptor.incrementalPollPeriodSecs", "900");
    addKey("transform.pipeline", "");
    // 1 MiB.
    addKey("transform.maxDocumentBytes", "1048576");
    addKey("transform.required", "false");
  }

  public Set<String> getAllKeys() {
    return config.stringPropertyNames();
  }

  /* Preferences requiring you to set them: */
  /**
   * Required to be set: GSA machine to send document ids to. This is the
   * hostname of your GSA on your network.
   */
  public String getGsaHostname() {
    return getValue("gsa.hostname");
  }

  /* Preferences suggested you set them: */

  public String getFeedName() {
    return getValue("feed.name");
  }

  /**
   * Suggested to be set: Local port, on this computer, onto which requests from
   * GSA come in on.
   */
  public int getServerPort() {
    return Integer.parseInt(getValue("server.port"));
  }

  /**
   * Local port, on this computer, from which the dashboard is served.
   */
  public int getServerDashboardPort() {
    return Integer.parseInt(getValue("server.dashboardPort"));
  }

  /* More sophisticated preferences that can be left
   unmodified for simple deployment and initial POC: */
  /**
   * Optional (default false): If your DocIds are already valid URLs you can
   * have this method return true and they will be sent to GSA unmodified. If
   * your DocId is like http://procurement.corp.company.com/internal/011212.html
   * you can turn this true and that URL will be handed to the GSA.
   *
   * <p>By default DocIds are URL encoded and prefixed with http:// and this
   * host's name and port.
   */
  public boolean isDocIdUrl() {
    return Boolean.parseBoolean(getValue("docId.isUrl"));
  }

  /** Without changes contains InetAddress.getLocalHost().getHostName(). */
  public String getServerHostname() {
    return getValue("server.hostname");
  }

  /**
   * Comma-separated list of IPs or hostnames that can retrieve content without
   * authentication checks. The GSA's hostname is implicitly in this list.
   *
   * <p>When in secure mode, clients are requested to provide a client
   * certificate. If the provided client certificate is valid and the Common
   * Name (CN) of the Subject is in this list (case-insensitively), then it is
   * given access.
   *
   * <p>In non-secure mode, the hostnames in this list are resolved to IPs at
   * startup and when a request is made from one of those IPs the client is
   * given access.
   */
  public String[] getServerFullAccessHosts() {
    return getValue("server.fullAccessHosts").split(",");
  }

  /**
   * Optional: Returns this host's base URI which other paths will be resolved
   * against. It is used to construct URIs to provide to the GSA for it to
   * contact this server for various services. For documents (which is probably
   * what you care about), the {@link #getServerBaseUri(DocId)} version is used
   * instead.
   *
   * <p>It must contain the protocol, hostname, and port, but may optionally
   * contain a path like {@code /yourfavoritepath}. By default, the protocol,
   * hostname, and port are retrieved automatically and no path is set.
   */
  public URI getServerBaseUri() {
    String protocol = isServerSecure() ? "https" : "http";
    return URI.create(protocol + "://" + getServerHostname() + ":"
                      + getServerPort());
  }

  /**
   * Optional: Path below {@link #getServerBaseUri(DocId)} where documents are
   * namespaced. Generally, should be at least {@code "/"} and end with a slash.
   */
  public String getServerDocIdPath() {
    return getValue("server.docIdPath");
  }

  /**
   * Optional: Returns the host's base URI which GSA will contact for document
   * information, including document contents. By default it returns {@link
   * #getServerBaseUri()}.  However, if you would like to direct GSA's queries
   * for contents to go to other computers/binaries then you can change this
   * method.
   *
   * <p>For example, imagine that you want five binaries to serve the contents
   * of files to the GSA.  In this case you could split the document ids into
   * five categories using something like:
   *
   * <pre>String urlBeginnings[] = new String[] {
   *   "http://content-server-A:5678",
   *   "http://content-server-B:5678",
   *   "http://backup-server-A:5678",
   *   "http://backup-server-B:5678",
   *   "http://new-server:7878"
   * };
   * int shard = docId.getUniqueId().hashCode() % 5;
   * return URI.create(urlBeginnings[shard]);</pre>
   *
   * <p>Note that this URI is used in conjunction with {@link
   * #getServerDocIdPath} and the document ID to form the full URL. In addition,
   * by using {@link #getServerBaseUri()} and {@code getDocIdPath()}, we have to
   * be able to parse back the original document ID when a request comes to this
   * server.
   */
  public URI getServerBaseUri(DocId docId) {
    return getServerBaseUri();
  }

  /**
   * Whether full security should be enabled. When {@code true}, the adaptor is
   * locked down using HTTPS, checks certificates, and generally behaves in a
   * fully-secure manner. When {@code false} (default), the adaptor serves
   * content over HTTP and is unable to authenticate users (all users are
   * treated as anonymous).
   *
   * <p>The need for this setting is because when enabled, security requires a
   * reasonable amount of configuration and know-how. To provide easy
   * out-of-the-box execution, this is disabled by default.
   */
  public boolean isServerSecure() {
    return Boolean.parseBoolean(getValue("server.secure"));
  }

  /**
   * The alias in the keystore that has the key to use for encryption.
   */
  public String getServerKeyAlias() {
    return getValue("server.keyAlias");
  }

  /**
   * The maximum number of worker threads to use to respond to document
   * requests. The main reason to limit the number of threads is that each can
   * be using a transform pipeline and will have multiple complete copies of the
   * response in memory at the same time.
   */
  public int getServerMaxWorkerThreads() {
    return Integer.parseInt(getValue("server.maxWorkerThreads"));
  }

  /**
   * The maximum request queue length.
   */
  public int getServerQueueCapacity() {
    return Integer.parseInt(getValue("server.queueCapacity"));
  }

  public boolean isServerToUseCompression() {
    return Boolean.parseBoolean(getValue("server.useCompression"));
  }

  /**
   * Optional (default false): Adds no-recrawl bit with sent records in feed
   * file. If connector handles updates and deletes then GSA does not have to
   * recrawl periodically to notice that a document is changed or deleted.
   */
  public boolean isFeedNoRecrawlBitEnabled() {
    return Boolean.getBoolean(getValue("feed.noRecrawlBitEnabled"));
  }

  /**
   * Optional (default false): Adds crawl-immediately bit with sent records in
   * feed file.  This bit makes the sent URL get crawl priority.
   */
  public boolean isCrawlImmediatelyBitEnabled() {
    return Boolean.parseBoolean(getValue("feed.crawlImmediatelyBitEnabled"));
  }

  /**
   * Whether the default {@code main()} should automatically start pushing all
   * document ids on startup. Defaults to {@code true}.
   */
  public boolean isAdaptorPushDocIdsOnStartup() {
    return Boolean.parseBoolean(getValue("adaptor.pushDocIdsOnStartup"));
  }

  /**
   * Automatically unzips and {@code DocId}s ending in {@code .zip} and provides
   * them to the GSA.
   */
  public boolean useAdaptorAutoUnzip() {
    return Boolean.parseBoolean(getValue("adaptor.autoUnzip"));
  } 

  /**
   * Cron-style format for describing when the adaptor should perform full
   * listings of {@code DocId}s. Multiple times can be specified by separating
   * them with a '|' (vertical bar).
   */
  public String getAdaptorFullListingSchedule() {
    return getValue("adaptor.fullListingSchedule");
  }

  public long getAdaptorIncrementalPollPeriodMillis() {
    return Long.parseLong(getValue("adaptor.incrementalPollPeriodSecs")) * 1000;
  }

  /**
   * Returns a list of maps correspending to each transform in the pipeline.
   * Each map is the configuration entries for that transform. The 'name'
   * configuration entry is added in each map based on the name provided by the
   * user.
   */
  public synchronized List<Map<String, String>> getTransformPipelineSpec() {
    final String configKey = "transform.pipeline";
    String configValue = getValue(configKey).trim();
    if ("".equals(configValue)) {
      return Collections.emptyList();
    }
    String[] items = getValue(configKey).split(",");
    List<Map<String, String>> transforms
        = new ArrayList<Map<String, String>>(items.length);
    for (String item : items) {
      item = item.trim();
      if ("".equals(item)) {
        throw new RuntimeException("Invalid format: " + configValue);
      }
      Map<String, String> params
          = getValuesWithPrefix(configKey + "." + item + ".");
      params.put("name", item);
      transforms.add(params);
    }
    return transforms;
  }

  public int getTransformMaxDocumentBytes() {
    return Integer.parseInt(getValue("transform.maxDocumentBytes"));
  }

  public boolean isTransformRequired() {
    return Boolean.parseBoolean(getValue("transform.required"));
  }

// TODO(pjo): Implement on GSA
//  /**
//   * Optional (default false): Adds no-follow bit with sent records in feed
//   * file. No-follow means that if document content has links they are not
//   * followed.
//   */
//  public boolean isNoFollowBitEnabled() {
//    return Boolean.parseBoolean(getValue("feed.noFollowBitEnabled"));
//  }

  /* Preferences expected to never change: */

  /** Provides the character encoding the GSA prefers. */
  public Charset getGsaCharacterEncoding() {
    return Charset.forName(getValue("gsa.characterEncoding"));
  }

  /**
   * Provides max number of URLs (equal to number of document ids) that are sent
   * to the GSA per feed file.
   */
  public int getFeedMaxUrls() {
    return Integer.parseInt(getValue("feed.maxUrls"));
  }

  /**
   * Load user-provided configuration file.
   */
  public synchronized void load(String configFile) throws IOException {
    load(new File(configFile));
  }

  /**
   * Load user-provided configuration file.
   */
  public synchronized void load(File configFile) throws IOException {
    this.configFile = configFile;
    configFileLastModified = configFile.lastModified();
    Reader reader = createReader(configFile);
    try {
      load(reader);
    } finally {
      reader.close();
    }
  }

  /**
   * Load user-provided configuration file, replacing any previously loaded file
   * configuration.
   */
  private void load(Reader configFile) throws IOException {
    Properties newConfigFileProperties = new Properties(defaultConfig);
    newConfigFileProperties.load(configFile);

    Config fakeOldConfig;
    Set<String> differentKeys;
    synchronized (this) {
      // Create replacement config.
      Properties newConfig = new Properties(newConfigFileProperties);
      for (Object o : config.keySet()) {
        newConfig.put(o, config.get(o));
      }

      // Find differences.
      differentKeys = findDifferences(config, newConfig);

      if (differentKeys.isEmpty()) {
        log.info("No configuration changes found");
        return;
      }

      validate(newConfig);

      fakeOldConfig = new Config();
      fakeOldConfig.config = config;
      this.config = newConfig;
    }
    log.info("New configuration file loaded");
    fireConfigModificationEvent(fakeOldConfig, differentKeys);
  }

  Reader createReader(File configFile) throws IOException {
    return new InputStreamReader(new BufferedInputStream(
        new FileInputStream(configFile)), Charset.forName("UTF-8"));
  }

  /**
   * @return {@code true} if configuration file was modified.
   */
  public boolean ensureLatestConfigLoaded() throws IOException {
    synchronized (this) {
      if (configFile == null || !configFile.exists() || !configFile.isFile()) {
        return false;
      }
      // Check for modifications.
      long newLastModified = configFile.lastModified();
      if (configFileLastModified == newLastModified || newLastModified == 0) {
        return false;
      }
      log.info("Noticed modified configuration file");

      load(configFile);
    }
    return true;
  }

  private Set<String> findDifferences(Properties config, Properties newConfig) {
    Set<String> differentKeys = new HashSet<String>();
    Set<String> names = new HashSet<String>();
    names.addAll(config.stringPropertyNames());
    names.addAll(newConfig.stringPropertyNames());
    for (String name : names) {
      String value = config.getProperty(name);
      String newValue = newConfig.getProperty(name);
      boolean equal = (value == null && newValue == null)
          || (value != null && value.equals(newValue));
      if (!equal) {
        differentKeys.add(name);
      }
    }
    return differentKeys;
  }

  /**
   * Loads {@code adaptor-config.properties} in the current directory, if it
   * exists. It squelches any errors so that you are free to call it without
   * error handling, since this is typically non-fatal.
   */
  public void loadDefaultConfigFile() {
    configFile = defaultConfigFile;
    if (configFile.exists() && configFile.isFile()) {
      try {
        load(configFile);
      } catch (IOException ex) {
        System.err.println("Exception when reading " + configFile);
        ex.printStackTrace(System.err);
      }
    }
  }

  public void validate() {
    validate(config);
  }

  private void validate(Properties config) {
    Set<String> unset = new HashSet<String>();
    for (String key : noDefaultConfig) {
      if (config.getProperty(key) == null) {
        unset.add(key);
      }
    }
    if (unset.size() != 0) {
      throw new IllegalStateException("Missing configuration values: " + unset);
    }
  }

  /**
   * Load default configuration file and parse command line options.
   *
   * @return unused command line arguments
   * @throws IllegalStateException when not all configuration keys have values
   */
  public String[] autoConfig(String[] args) {
    int i;
    for (i = 0; i < args.length; i++) {
      if (!args[i].startsWith("-D")) {
        break;
      }
      String arg = args[i].substring(2);
      String[] parts = arg.split("=", 2);
      if (parts.length < 2) {
        break;
      }
      setValue(parts[0], parts[1]);
    }
    loadDefaultConfigFile();
    validate();
    if (i == 0) {
      return args;
    } else {
      return Arrays.copyOfRange(args, i, args.length);
    }
  }

  /**
   * Get a configuration value. Never returns {@code null}.
   *
   * @throws IllegalStateException if {@code key} has no value
   */
  public String getValue(String key) {
    String value = config.getProperty(key);
    if (value == null) {
      throw new IllegalStateException(MessageFormat.format(
          "You must set configuration key ''{0}''.", key));
    }
    return value;
  }

  /**
   * Gets all configuration values that begin with {@code prefix}, returning
   * them as a map with the keys having {@code prefix} removed.
   */
  public synchronized Map<String, String> getValuesWithPrefix(String prefix) {
    Map<String, String> values = new HashMap<String, String>();
    for (String key : config.stringPropertyNames()) {
      if (!key.startsWith(prefix)) {
        continue;
      }
      values.put(key.substring(prefix.length()), config.getProperty(key));
    }
    return values;
  }

  /**
   * Add configuration key. If {@code defaultValue} is {@code null}, then no
   * default value is used and the user must provide one.
   */
  public synchronized void addKey(String key, String defaultValue) {
    if (defaultConfig.contains(key) || noDefaultConfig.contains(key)) {
      throw new IllegalStateException("Key already added: " + key);
    }
    if (defaultValue == null) {
      noDefaultConfig.add(key);
    } else {
      defaultConfig.setProperty(key, defaultValue);
    }
  }

  /**
   * Change the default value of a preexisting configuration key. If {@code
   * defaultValue} is {@code null}, then no default is used and the user must
   * provide one.
   */
  public synchronized void overrideKey(String key, String defaultValue) {
    if (!defaultConfig.contains(key) && !noDefaultConfig.contains(key)) {
      log.log(Level.WARNING, "Overriding unknown configuration key: {0}", key);
    }
    defaultConfig.remove(key);
    noDefaultConfig.remove(key);
    if (defaultValue == null) {
      noDefaultConfig.add(key);
    } else {
      defaultConfig.setProperty(key, defaultValue);
    }
  }

  /**
   * Manually set a configuration value. Depending on when called, it can
   * override a user's configuration, which should be avoided.
   */
  synchronized void setValue(String key, String value) {
    config.setProperty(key, value);
  }

  public void addConfigModificationListener(
      ConfigModificationListener listener) {
    modificationListeners.add(listener);
  }

  public void removeConfigModificationListener(
      ConfigModificationListener listener) {
    modificationListeners.remove(listener);
  }

  private void fireConfigModificationEvent(Config oldConfig,
                                           Set<String> modifiedKeys) {
    ConfigModificationEvent ev
        = new ConfigModificationEvent(this, oldConfig, modifiedKeys);
    for (ConfigModificationListener listener : modificationListeners) {
      try {
        listener.configModified(ev);
      } catch (Exception ex) {
        log.log(Level.WARNING,
                "Unexpected exception. Consider filing a bug.", ex);
      }
    }
  }
}
