/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.metrics.reporters.solr;

import java.nio.file.Paths;
import java.util.Map;

import com.codahale.metrics.Metric;
import org.apache.commons.io.IOUtils;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrCore;
import org.apache.solr.metrics.AggregateMetric;
import org.apache.solr.metrics.SolrMetricManager;
import org.apache.solr.metrics.SolrMetricReporter;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *
 */
public class SolrCloudReportersTest extends SolrCloudTestCase {
  int leaderRegistries;
  int overseerRegistries;


  @BeforeClass
  public static void configureDummyCluster() throws Exception {
    configureCluster(0).configure();
  }

  @Before
  public void closePreviousCluster() throws Exception {
    shutdownCluster();
    leaderRegistries = 0;
    overseerRegistries = 0;
  }

  @Test
  public void testExplicitConfiguration() throws Exception {
    String solrXml = IOUtils.toString(SolrCloudReportersTest.class.getResourceAsStream("/solr/solr-solrreporter.xml"), "UTF-8");
    configureCluster(2)
        .withSolrXml(solrXml).configure();
    cluster.uploadConfigSet(Paths.get(TEST_PATH().toString(), "configsets", "minimal", "conf"), "test");
    System.out.println("ZK: " + cluster.getZkServer().getZkAddress());
    CollectionAdminRequest.createCollection("test_collection", "test", 2, 2)
        .setMaxShardsPerNode(4)
        .process(cluster.getSolrClient());
    waitForState("Expected test_collection with 2 shards and 2 replicas", "test_collection", clusterShape(2, 2));
    Thread.sleep(15000);
    cluster.getJettySolrRunners().forEach(jetty -> {
      CoreContainer cc = jetty.getCoreContainer();
      SolrMetricManager metricManager = cc.getMetricManager();
      Map<String, SolrMetricReporter> reporters = metricManager.getReporters("solr.overseer");
      assertEquals(reporters.toString(), 1, reporters.size());
      SolrMetricReporter reporter = reporters.get("test");
      assertNotNull(reporter);
      assertTrue(reporter.toString(), reporter instanceof SolrOverseerReporter);
      SolrOverseerReporter sor = (SolrOverseerReporter)reporter;
      assertEquals(5, sor.getPeriod());
      for (String registryName : metricManager.registryNames(".*\\.shard[0-9]\\.core.*")) {
        reporters = metricManager.getReporters(registryName);
        assertEquals(reporters.toString(), 1, reporters.size());
        reporter = null;
        for (String name : reporters.keySet()) {
          if (name.startsWith("test")) {
            reporter = reporters.get(name);
          }
        }
        assertNotNull(reporter);
        assertTrue(reporter.toString(), reporter instanceof SolrShardReporter);
        SolrShardReporter srr = (SolrShardReporter)reporter;
        assertEquals(5, srr.getPeriod());
      }
      for (String registryName : metricManager.registryNames(".*\\.leader")) {
        leaderRegistries++;
        reporters = metricManager.getReporters(registryName);
        // no reporters registered for leader registry
        assertEquals(reporters.toString(), 0, reporters.size());
        // verify specific metrics
        Map<String, Metric> metrics = metricManager.registry(registryName).getMetrics();
        String key = "QUERY./select.requests.count";
        assertTrue(key, metrics.containsKey(key));
        assertTrue(key, metrics.get(key) instanceof AggregateMetric);
        key = "UPDATE./update/json.requests.count";
        assertTrue(key, metrics.containsKey(key));
        assertTrue(key, metrics.get(key) instanceof AggregateMetric);
      }
      if (metricManager.registryNames().contains("solr.overseer")) {
        overseerRegistries++;
        Map<String,Metric> metrics = metricManager.registry("solr.overseer").getMetrics();
        String key = "jvm.memory.heap.init.value";
        assertTrue(key, metrics.containsKey(key));
        assertTrue(key, metrics.get(key) instanceof AggregateMetric);
        key = "leader.test_collection.shard1.UPDATE./update/json.requests.count.max";
        assertTrue(key, metrics.containsKey(key));
        assertTrue(key, metrics.get(key) instanceof AggregateMetric);
      }
    });
    assertEquals("leaderRegistries", 2, leaderRegistries);
    assertEquals("overseerRegistries", 1, overseerRegistries);
  }

  @Test
  public void testDefaultPlugins() throws Exception {
    String solrXml = IOUtils.toString(SolrCloudReportersTest.class.getResourceAsStream("/solr/solr.xml"), "UTF-8");
    configureCluster(2)
        .withSolrXml(solrXml).configure();
    cluster.uploadConfigSet(Paths.get(TEST_PATH().toString(), "configsets", "minimal", "conf"), "test");
    System.out.println("ZK: " + cluster.getZkServer().getZkAddress());
    CollectionAdminRequest.createCollection("test_collection", "test", 2, 2)
        .setMaxShardsPerNode(4)
        .process(cluster.getSolrClient());
    waitForState("Expected test_collection with 2 shards and 2 replicas", "test_collection", clusterShape(2, 2));
    // has to wait at least twice the SolrMetricManager.DEFAULT_CLOUD_REPORTER_PERIOD for the first
    // report to aggregate into a *.leader registry, and for the second report to aggregate from *.leader
    // into solr.overseer
    Thread.sleep(SolrMetricManager.DEFAULT_CLOUD_REPORTER_PERIOD * 3 * 1000);
    cluster.getJettySolrRunners().forEach(jetty -> {
      CoreContainer cc = jetty.getCoreContainer();
      // verify registry names
      for (String name : cc.getCoreNames()) {
        SolrCore core = cc.getCore(name);
        try {
          String registryName = core.getCoreMetricManager().getRegistryName();
          String leaderRegistryName = core.getCoreMetricManager().getLeaderRegistryName();
          String coreName = core.getName();
          String collectionName = core.getCoreDescriptor().getCollectionName();
          String coreNodeName = core.getCoreDescriptor().getCloudDescriptor().getCoreNodeName();
          String shardId = core.getCoreDescriptor().getCloudDescriptor().getShardId();

          assertEquals("solr.core." + collectionName + "." + shardId + "." + coreNodeName, registryName);
          assertEquals("solr.core." + collectionName + "." + shardId + ".leader", leaderRegistryName);

        } finally {
          if (core != null) {
            core.close();
          }
        }
      }
      SolrMetricManager metricManager = cc.getMetricManager();
      Map<String, SolrMetricReporter> reporters = metricManager.getReporters("solr.overseer");
      assertEquals(reporters.toString(), 1, reporters.size());
      SolrMetricReporter reporter = reporters.get("overseerDefault");
      assertNotNull(reporter);
      assertTrue(reporter.toString(), reporter instanceof SolrOverseerReporter);
      SolrOverseerReporter sor = (SolrOverseerReporter)reporter;
      assertEquals(SolrMetricManager.DEFAULT_CLOUD_REPORTER_PERIOD, sor.getPeriod());
      for (String registryName : metricManager.registryNames(".*\\.shard[0-9]\\.core.*")) {
        reporters = metricManager.getReporters(registryName);
        assertEquals(reporters.toString(), 1, reporters.size());
        reporter = null;
        for (String name : reporters.keySet()) {
          if (name.startsWith("shardDefault")) {
            reporter = reporters.get(name);
          }
        }
        assertNotNull(reporter);
        assertTrue(reporter.toString(), reporter instanceof SolrShardReporter);
        SolrShardReporter srr = (SolrShardReporter)reporter;
        assertEquals(SolrMetricManager.DEFAULT_CLOUD_REPORTER_PERIOD, srr.getPeriod());
      }
      for (String registryName : metricManager.registryNames(".*\\.leader")) {
        leaderRegistries++;
        reporters = metricManager.getReporters(registryName);
        // no reporters registered for leader registry
        assertEquals(reporters.toString(), 0, reporters.size());
      }
      if (metricManager.registryNames().contains("solr.overseer")) {
        overseerRegistries++;
        Map<String,Metric> metrics = metricManager.registry("solr.overseer").getMetrics();
        String key = "jvm.memory.heap.init.value";
        assertTrue(key, metrics.containsKey(key));
        assertTrue(key, metrics.get(key) instanceof AggregateMetric);
        key = "leader.test_collection.shard1.UPDATE./update/json.requests.count.max";
        assertTrue(key, metrics.containsKey(key));
        assertTrue(key, metrics.get(key) instanceof AggregateMetric);
      }
    });
    assertEquals("leaderRegistries", 2, leaderRegistries);
    assertEquals("overseerRegistries", 1, overseerRegistries);
  }
}
