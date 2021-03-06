/*
 * Copyright 2020 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.connect.elasticsearch.integration;

import static io.confluent.connect.elasticsearch.ElasticsearchSinkConnectorConfig.BATCH_SIZE_CONFIG;
import static io.confluent.connect.elasticsearch.ElasticsearchSinkConnectorConfig.BEHAVIOR_ON_NULL_VALUES_CONFIG;
import static io.confluent.connect.elasticsearch.ElasticsearchSinkConnectorConfig.IGNORE_KEY_CONFIG;
import static io.confluent.connect.elasticsearch.ElasticsearchSinkConnectorConfig.LINGER_MS_CONFIG;
import static io.confluent.connect.elasticsearch.ElasticsearchSinkConnectorConfig.WRITE_METHOD_CONFIG;
import static org.apache.kafka.connect.runtime.ConnectorConfig.VALUE_CONVERTER_CLASS_CONFIG;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.confluent.connect.elasticsearch.DataConverter.BehaviorOnNullValues;
import io.confluent.connect.elasticsearch.Mapping;
import io.confluent.connect.elasticsearch.TestUtils;
import io.confluent.connect.elasticsearch.jest.JestElasticsearchClient.WriteMethod;
import java.util.Collections;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.storage.StringConverter;
import org.apache.kafka.test.IntegrationTest;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Category(IntegrationTest.class)
public class ElasticsearchConnectorIT extends ElasticsearchConnectorBaseIT {

  private static Logger log = LoggerFactory.getLogger(ElasticsearchConnectorIT.class);

  @BeforeClass
  public static void setupBeforeAll() {
    container = ElasticsearchContainer.fromSystemProperties();
    container.start();
  }

  @Test
  public void testChangeConfigsAndRestart() throws Exception {
    // run connector and write
    runSimpleTest(props);

    // restart
    props.put(BATCH_SIZE_CONFIG, "10");
    props.put(LINGER_MS_CONFIG, "1000");
    connect.configureConnector(CONNECTOR_NAME, props);

    // write some more
    writeRecords(NUM_RECORDS);
    verifySearchResults(NUM_RECORDS * 2);
  }

  @Test
  public void testDelete() throws Exception {
    props.put(BEHAVIOR_ON_NULL_VALUES_CONFIG, BehaviorOnNullValues.DELETE.name());
    props.put(IGNORE_KEY_CONFIG, "false");
    runSimpleTest(props);

    // should have 5 records at this point
    // try deleting last one
    int lastRecord = NUM_RECORDS - 1;
    connect.kafka().produce(TOPIC, String.valueOf(lastRecord), null);

    // should have one less records
    verifySearchResults(NUM_RECORDS - 1);
  }

  @Test
  public void testHappyPath() throws Exception {
    runSimpleTest(props);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testMapping() throws Exception {
    client.createIndices(Collections.singleton(TOPIC));
    Schema schema = TestUtils.createSchema();
    Mapping.createMapping(client, TOPIC, TYPE, schema);

    JsonObject mapping = Mapping.getMapping(client, TOPIC, TYPE);
    assertNotNull(mapping);
    TestUtils.verifyMapping(client, schema, mapping);
  }

  @Test
  public void testNullValue() throws Exception {
    runSimpleTest(props);

    // should have 5 records at this point
    // try writing null value
    connect.kafka().produce(TOPIC, String.valueOf(NUM_RECORDS), null);

    // should still have 5 records
    verifySearchResults(NUM_RECORDS);
  }

  /*
   * Currently writing primitives to ES fails because ES expects a JSON document and the connector
   * does not wrap primitives in any way into a JSON document.
   */
  @Test
  public void testPrimitive() throws Exception {
    props.put(VALUE_CONVERTER_CLASS_CONFIG, StringConverter.class.getName());

    connect.configureConnector(CONNECTOR_NAME, props);

    // wait for tasks to spin up
    waitForConnectorToStart(CONNECTOR_NAME, TASKS_MAX);

    for (int i  = 0; i < NUM_RECORDS; i++) {
      connect.kafka().produce(TOPIC, String.valueOf(i),  String.valueOf(i));
    }

    waitForRecords(0);
  }

  @Test
  public void testUpsert() throws Exception {
    props.put(WRITE_METHOD_CONFIG, WriteMethod.UPSERT.toString());
    props.put(IGNORE_KEY_CONFIG, "false");
    runSimpleTest(props);

    // should have 10 records at this point
    // try updating last one
    int lastRecord = NUM_RECORDS - 1;
    connect.kafka().produce(TOPIC, String.valueOf(lastRecord), String.format("{\"doc_num\":%d}", 0));
    writeRecordsFromIndex(NUM_RECORDS, NUM_RECORDS);

    // should have double number of records
    verifySearchResults(NUM_RECORDS * 2);

    JsonObject result = client.search("", TOPIC, null);
    JsonArray rawHits = result.getAsJsonObject("hits").getAsJsonArray("hits");

    for (int i = 0; i < rawHits.size(); ++i) {
      JsonObject hitData = rawHits.get(i).getAsJsonObject();
      JsonObject source = hitData.get("_source").getAsJsonObject();
      assertTrue(source.has("doc_num"));
      if (Integer.valueOf(hitData.get("_id").getAsString()) == lastRecord) {
        // last record should be updated
        assertTrue(source.get("doc_num").getAsInt() == 0);
      }
    }
  }
}
