/*
 * Copyright © 2018-2020 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.cdap.runtime.spi.provisioner.dataproc;

import io.cdap.cdap.runtime.spi.ProgramRunInfo;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tests for Dataproc provisioner
 */
public class DataprocProvisionerTest {

  @Test
  public void testClusterName() {
    // test basic
    ProgramRunInfo programRunInfo = new ProgramRunInfo.Builder()
      .setNamespace("ns")
      .setApplication("app")
      .setVersion("1.0")
      .setProgramType("workflow")
      .setProgram("program")
      .setRun(UUID.randomUUID().toString())
      .build();
    Assert.assertEquals("cdap-app-" + programRunInfo.getRun(),
                        new DataprocProvisioner().getClusterName(new MockProvisionerContext(programRunInfo)));

    // test lowercasing, stripping of invalid characters, and truncation
    programRunInfo = new ProgramRunInfo.Builder()
      .setNamespace("ns")
      .setApplication("My@Appl!cation")
      .setVersion("1.0")
      .setProgramType("workflow")
      .setProgram("program")
      .setRun(UUID.randomUUID().toString())
      .build();
    Assert.assertEquals("cdap-myapplcat-" + programRunInfo.getRun(),
                        new DataprocProvisioner().getClusterName(new MockProvisionerContext(programRunInfo)));
  }

  @Test
  public void testDataprocConf() {
    Map<String, String> props = new HashMap<>();
    props.put(DataprocConf.PROJECT_ID_KEY, "pid");
    props.put("accountKey", "key");
    props.put("region", "region1");
    props.put("zone", "region1-a");
    props.put("network", "network");
    props.put("spark:spark.reducer.maxSizeInFlight", "100");
    props.put("hadoop-env:MAPREDUCE_CLASSPATH", "xyz");
    props.put("dataproc:am.primary_only", "true");
    props.put("clusterMetaData", "metadata-key1|metadata-val1;metadata-key2|metadata-val2");

    DataprocConf conf = DataprocConf.create(props);

    Assert.assertEquals("pid", conf.getProjectId());
    Assert.assertEquals("region1", conf.getRegion());
    Assert.assertEquals("region1-a", conf.getZone());
    Map<String, String> clusterMetaData = conf.getClusterMetaData();
    Assert.assertEquals("metadata-val1", clusterMetaData.get("metadata-key1"));
    Assert.assertEquals("metadata-val2", clusterMetaData.get("metadata-key2"));

    Map<String, String> dataprocProps = conf.getClusterProperties();
    Assert.assertEquals(3, dataprocProps.size());

    Assert.assertEquals("100", dataprocProps.get("spark:spark.reducer.maxSizeInFlight"));
    Assert.assertEquals("xyz", dataprocProps.get("hadoop-env:MAPREDUCE_CLASSPATH"));
    Assert.assertEquals("true", dataprocProps.get("dataproc:am.primary_only"));
  }

  @Test
  public void testAutoZone() {
    Map<String, String> props = new HashMap<>();
    props.put(DataprocConf.PROJECT_ID_KEY, "pid");
    props.put("accountKey", "key");
    props.put("region", "region1");
    props.put("zone", "auto-detect");
    props.put("network", "network");

    DataprocConf conf = DataprocConf.create(props);
    Assert.assertNull(conf.getZone());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testInvalidZoneCheck() {
    Map<String, String> props = new HashMap<>();
    props.put(DataprocConf.PROJECT_ID_KEY, "pid");
    props.put("accountKey", "key");
    props.put("region", "region1");
    props.put("zone", "region2-a");
    props.put("network", "network");

    DataprocConf.create(props);
  }

  @Test
  public void testCreateContextProperties() {
    MockProvisionerSystemContext provisionerSystemContext = new MockProvisionerSystemContext();
    String resourceMaxPercentKey = "capacity-scheduler:yarn.scheduler.capacity.maximum-am-resource-percent";
    String resourceMaxPercentVal = "0.5";
    String clusterMetaData = "metadata-key1|metadata-val1;metadata-key2|metadata-val2";

    //default system properties defined by DataprocProvisioner
    provisionerSystemContext.addProperty(DataprocConf.NETWORK, "old-network");
    provisionerSystemContext.addProperty(DataprocConf.STACKDRIVER_LOGGING_ENABLED, "true");
    provisionerSystemContext
      .addProperty(DataprocConf.CLUSTER_MEATA_DATA, clusterMetaData);

    //default system properties defined by AbstractDataprocProvisioner
    provisionerSystemContext.addProperty(resourceMaxPercentKey, resourceMaxPercentVal);
    provisionerSystemContext.addProperty(DataprocConf.RUNTIME_JOB_MANAGER, "job_manager");

    //non-default system properties
    provisionerSystemContext.addProperty("non-system-default-key", "any-value");

    DataprocProvisioner provisioner = new DataprocProvisioner();
    provisioner.initialize(provisionerSystemContext);

    MockProvisionerContext provisionerContext = new MockProvisionerContext();
    final String network = "test-network";
    provisionerContext.addProperty(DataprocConf.NETWORK, network);

    Map<String, String> properties = provisioner.createContextProperties(provisionerContext);

    Assert.assertEquals(network, properties.get(DataprocConf.NETWORK));
    Assert.assertEquals("true", properties.get(DataprocConf.STACKDRIVER_LOGGING_ENABLED));
    Assert.assertEquals(resourceMaxPercentVal, properties.get(resourceMaxPercentKey));
    Assert.assertEquals(clusterMetaData, properties.get(DataprocConf.CLUSTER_MEATA_DATA));
    Assert.assertEquals("job_manager", properties.get(DataprocConf.RUNTIME_JOB_MANAGER));
    Assert.assertNull(properties.get("non-system-default-key"));
  }

  @Test
  public void testCustomImageURI() {
    Map<String, String> props = new HashMap<>();
    String customURI = "https://www.googleapis.com/compute/v1/projects/p1/global/images/testimage";
    props.put(DataprocConf.CUSTOM_IMAGE_URI,
              customURI);
    props.put("accountKey", "key");
    props.put("projectId", "my project");
    props.put("zone", "region1-a");

    DataprocConf conf = DataprocConf.create(props);
    Assert.assertEquals(customURI, conf.getCustomImageUri());
  }

}
