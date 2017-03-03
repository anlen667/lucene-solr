package org.apache.solr.metrics.reporters.solr;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.apache.http.client.HttpClient;
import org.apache.solr.cloud.Overseer;
import org.apache.solr.cloud.ZkController;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrInfoMBean;
import org.apache.solr.handler.admin.MetricsCollectorHandler;
import org.apache.solr.metrics.SolrMetricManager;
import org.apache.solr.metrics.SolrMetricReporter;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This reporter sends selected metrics from local registries to {@link Overseer}.
 * <p>The following configuration properties are supported:</p>
 * <ul>
 *   <li>handler - (optional str) handler path where reports are sent. Default is
 *   {@link MetricsCollectorHandler#HANDLER_PATH}.</li>
 *   <li>period - (optional int) how often reports are sent, in seconds. Default is 60. Setting this
 *   to 0 disables the reporter.</li>
 *   <li>report - (optional multiple lst) report configuration(s), see below.</li>
 * </ul>
 * Each report configuration consist of the following properties:
 * <ul>
 *   <li>registry - (required str) regex pattern matching source registries (see {@link SolrMetricManager#registryNames(String...)}),
 *   may contain capture groups.</li>
 *   <li>group - (required str) target registry name where metrics will be grouped. This can be a regex pattern that
 *   contains back-references to capture groups collected by <code>registry</code> pattern</li>
 *   <li>label - (optional str) optional prefix to prepend to metric names, may contain back-references to
 *   capture groups collected by <code>registry</code> pattern</li>
 *   <li>filter - (optional multiple str) regex expression(s) matching selected metrics to be reported.</li>
 * </ul>
 * NOTE: this reporter uses predefined "overseer" group, and it's always created even if explicit configuration
 * is missing. Default configuration uses report specifications from {@link #DEFAULT_REPORTS}.
 * <p>Example configuration:</p>
 * <pre>
 *       &lt;reporter name="test" group="overseer"&gt;
 *         &lt;str name="handler"&gt;/admin/metrics/collector&lt;/str&gt;
 *         &lt;int name="period"&gt;11&lt;/int&gt;
 *         &lt;lst name="report"&gt;
 *           &lt;str name="group"&gt;overseer&lt;/str&gt;
 *           &lt;str name="label"&gt;jvm&lt;/str&gt;
 *           &lt;str name="registry"&gt;solr\.jvm&lt;/str&gt;
 *           &lt;str name="filter"&gt;memory\.total\..*&lt;/str&gt;
 *           &lt;str name="filter"&gt;memory\.heap\..*&lt;/str&gt;
 *           &lt;str name="filter"&gt;os\.SystemLoadAverage&lt;/str&gt;
 *           &lt;str name="filter"&gt;threads\.count&lt;/str&gt;
 *         &lt;/lst&gt;
 *         &lt;lst name="report"&gt;
 *           &lt;str name="group"&gt;overseer&lt;/str&gt;
 *           &lt;str name="label"&gt;leader.$1&lt;/str&gt;
 *           &lt;str name="registry"&gt;solr\.core\.(.*)\.leader&lt;/str&gt;
 *           &lt;str name="filter"&gt;UPDATE\./update/.*&lt;/str&gt;
 *         &lt;/lst&gt;
 *       &lt;/reporter&gt;
 * </pre>
 *
 */
public class SolrOverseerReporter extends SolrMetricReporter {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String OVERSEER_GROUP = SolrMetricManager.overridableRegistryName(SolrInfoMBean.Group.overseer.toString());

  public static final List<SolrReporter.Report> DEFAULT_REPORTS = new ArrayList<SolrReporter.Report>() {{
    add(new SolrReporter.Report(OVERSEER_GROUP, "jetty",
        SolrMetricManager.overridableRegistryName(SolrInfoMBean.Group.jetty.toString()),
        Collections.emptySet())); // all metrics
    add(new SolrReporter.Report(OVERSEER_GROUP, "jvm",
        SolrMetricManager.overridableRegistryName(SolrInfoMBean.Group.jvm.toString()),
        new HashSet<String>() {{
          add("memory\\.total\\..*");
          add("memory\\.heap\\..*");
          add("os\\.SystemLoadAverage");
          add("os\\.FreePhysicalMemorySize");
          add("os\\.FreeSwapSpaceSize");
          add("os\\.OpenFileDescriptorCount");
          add("threads\\.count");
        }})); // all metrics
    // XXX anything interesting here?
    //add(new SolrReporter.Specification(OVERSEER_GROUP, "node", SolrMetricManager.overridableRegistryName(SolrInfoMBean.Group.node.toString()),
    //    Collections.emptySet())); // all metrics
    add(new SolrReporter.Report(OVERSEER_GROUP, "leader.$1", "solr\\.core\\.(.*)\\.leader",
        new HashSet<String>(){{
          add("UPDATE\\./update/.*");
          add("QUERY\\./select.*");
          add("INDEX\\..*");
          add("TLOG\\..*");
    }}));
  }};

  private String handler = MetricsCollectorHandler.HANDLER_PATH;
  private int period = SolrMetricManager.DEFAULT_CLOUD_REPORTER_PERIOD;
  private List<SolrReporter.Report> reports = new ArrayList<>();

  private SolrReporter reporter;

  /**
   * Create a reporter for metrics managed in a named registry.
   *
   * @param metricManager metric manager
   * @param registryName  this is ignored
   */
  public SolrOverseerReporter(SolrMetricManager metricManager, String registryName) {
    super(metricManager, registryName);
  }

  public void setHandler(String handler) {
    this.handler = handler;
  }

  public void setPeriod(int period) {
    this.period = period;
  }

  public void setReport(List<Map> reportConfig) {
    if (reportConfig == null || reportConfig.isEmpty()) {
      return;
    }
    reportConfig.forEach(map -> {
      SolrReporter.Report r = SolrReporter.Report.fromMap(map);
      if (r != null) {
        reports.add(r);
      }
    });
  }

  // for unit tests
  int getPeriod() {
    return period;
  }

  List<SolrReporter.Report> getReports() {
    return reports;
  }

  @Override
  protected void validate() throws IllegalStateException {
    if (period < 1) {
      log.info("Turning off node reporter, period=" + period);
    }
    if (reports.isEmpty()) { // set defaults
      reports = DEFAULT_REPORTS;
    }
  }

  @Override
  public void close() throws IOException {
    if (reporter != null) {
      reporter.close();;
    }
  }

  public void setCoreContainer(CoreContainer cc) {
    if (reporter != null) {
      reporter.close();;
    }
    // start reporter only in cloud mode
    if (!cc.isZooKeeperAware()) {
      log.warn("Not ZK-aware, not starting...");
      return;
    }
    if (period < 1) { // don't start it
      return;
    }
    HttpClient httpClient = cc.getUpdateShardHandler().getHttpClient();
    ZkController zk = cc.getZkController();
    String reporterId = zk.getNodeName();
    reporter = SolrReporter.Builder.forReports(metricManager, reports)
        .convertRatesTo(TimeUnit.SECONDS)
        .convertDurationsTo(TimeUnit.MILLISECONDS)
        .withHandler(handler)
        .withReporterId(reporterId)
        .cloudClient(false) // we want to send reports specifically to a selected leader instance
        .skipAggregateValues(true) // we don't want to transport details of aggregates
        .skipHistograms(true) // we don't want to transport histograms
        .build(httpClient, new OverseerUrlSupplier(zk));

    reporter.start(period, TimeUnit.SECONDS);
  }

  // TODO: fix this when there is an elegant way to retrieve URL of a node that runs Overseer leader.
  // package visibility for unit tests
  static class OverseerUrlSupplier implements Supplier<String> {
    private static final long DEFAULT_INTERVAL = 30000; // 30s
    private ZkController zk;
    private String lastKnownUrl = null;
    private long lastCheckTime = 0;
    private long interval = DEFAULT_INTERVAL;

    OverseerUrlSupplier(ZkController zk) {
      this.zk = zk;
    }

    @Override
    public String get() {
      if (zk == null) {
        return null;
      }
      // primitive caching for lastKnownUrl
      long now = System.currentTimeMillis();
      if (lastKnownUrl != null && (now - lastCheckTime) < interval) {
        return lastKnownUrl;
      }
      if (!zk.isConnected()) {
        return lastKnownUrl;
      }
      lastCheckTime = now;
      SolrZkClient zkClient = zk.getZkClient();
      ZkNodeProps props;
      try {
        props = ZkNodeProps.load(zkClient.getData(
            Overseer.OVERSEER_ELECT + "/leader", null, null, true));
      } catch (KeeperException e) {
        log.warn("Could not obtain overseer's address, skipping.", e);
        return lastKnownUrl;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return lastKnownUrl;
      }
      if (props == null) {
        return lastKnownUrl;
      }
      String oid = props.getStr("id");
      if (oid == null) {
        return lastKnownUrl;
      }
      String[] ids = oid.split("-");
      if (ids.length != 3) { // unknown format
        log.warn("Unknown format of leader id, skipping: " + oid);
        return lastKnownUrl;
      }
      // convert nodeName back to URL
      String url = zk.getZkStateReader().getBaseUrlForNodeName(ids[1]);
      // check that it's parseable
      try {
        new java.net.URL(url);
      } catch (MalformedURLException mue) {
        log.warn("Malformed Overseer's leader URL: url", mue);
        return lastKnownUrl;
      }
      lastKnownUrl = url;
      return url;
    }
  }

}
