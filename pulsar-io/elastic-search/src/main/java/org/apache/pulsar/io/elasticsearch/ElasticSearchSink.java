/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.io.elasticsearch;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.KeyValue;
import org.apache.pulsar.io.core.Sink;
import org.apache.pulsar.io.core.SinkContext;
import org.apache.pulsar.io.core.annotations.Connector;
import org.apache.pulsar.io.core.annotations.IOType;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.bulk.*;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.CreateIndexResponse;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;

/**
 * The base abstract class for ElasticSearch sinks.
 * Users need to implement extractKeyValue function to use this sink.
 * This class assumes that the input will be JSON documents
 */
@Connector(
    name = "elastic_search",
    type = IOType.SINK,
    help = "A sink connector that sends pulsar messages to elastic search",
    configClass = ElasticSearchConfig.class
)
@Slf4j
public class ElasticSearchSink implements Sink<byte[]> {

    private URL url;
    private RestHighLevelClient client;
    private CredentialsProvider credentialsProvider;
    private ElasticSearchConfig elasticSearchConfig;
    private Processor processor;

    private interface Processor {
        void process(Request request);
        void close() throws Exception;
    }

    @Override
    public void open(Map<String, Object> config, SinkContext sinkContext) throws Exception {
        elasticSearchConfig = ElasticSearchConfig.load(config);
        elasticSearchConfig.validate();
        createIndexIfNeeded();
        getProcessor();
    }

    @Override
    public void close() throws Exception {
        getProcessor().close();
        client.close();
    }

    @Override
    public void write(Record<byte[]> record) {
        KeyValue<String, byte[]> keyValue = extractKeyValue(record);
        Request<byte[]> request = new Request<>(elasticSearchConfig.getIndexName(), record);
        request.source(keyValue.getValue(), XContentType.JSON);

        getProcessor().process(request);
    }

    public KeyValue<String, byte[]> extractKeyValue(Record<byte[]> record) {
        String key = record.getKey().orElse("");
        return new KeyValue<>(key, record.getValue());
    }

    private void createIndexIfNeeded() throws IOException {
        GetIndexRequest request = new GetIndexRequest(elasticSearchConfig.getIndexName());
        boolean exists = getClient().indices().exists(request, RequestOptions.DEFAULT);

        if (!exists) {
            CreateIndexRequest cireq = new CreateIndexRequest(elasticSearchConfig.getIndexName());

            cireq.settings(Settings.builder()
               .put("index.number_of_shards", elasticSearchConfig.getIndexNumberOfShards())
               .put("index.number_of_replicas", elasticSearchConfig.getIndexNumberOfReplicas()));

            CreateIndexResponse ciresp = getClient().indices().create(cireq,  RequestOptions.DEFAULT);
            if (!ciresp.isAcknowledged() || !ciresp.isShardsAcknowledged()) {
                throw new RuntimeException("Unable to create index.");
            }
        }
    }

    private URL getUrl() throws MalformedURLException {
        if (url == null) {
            url = new URL(elasticSearchConfig.getElasticSearchUrl());
        }
        return url;
    }

    private CredentialsProvider getCredentialsProvider() {

        if (StringUtils.isEmpty(elasticSearchConfig.getUsername())
            || StringUtils.isEmpty(elasticSearchConfig.getPassword())) {
            return null;
        }

        credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(elasticSearchConfig.getUsername(),
                        elasticSearchConfig.getPassword()));
        return credentialsProvider;
    }

    private RestHighLevelClient getClient() throws MalformedURLException {
        if (client == null) {
          CredentialsProvider cp = getCredentialsProvider();
          RestClientBuilder builder = RestClient.builder(new HttpHost(getUrl().getHost(),
                  getUrl().getPort(), getUrl().getProtocol()));

          if (cp != null) {
              builder.setHttpClientConfigCallback(httpClientBuilder ->
              httpClientBuilder.setDefaultCredentialsProvider(cp));
          }
          client = new RestHighLevelClient(builder);
        }
        return client;
    }

    private static class Request<T> extends IndexRequest {
        private Record<T> record;

        Request(String index, Record<T> record) {
            super(index);
            this.record = record;
        }

        Record<T> getRecord() {
            return record;
        }
    }

    private Processor getProcessor() {
        if (processor == null) {
            if (StringUtils.equalsIgnoreCase(elasticSearchConfig.getMode(), "bulk")) {
                System.out.println("Mode: Bulk");
                log.info("Mode: Bulk");
                processor = new BulkModeProcessor();
            } else {
                System.out.println("Mode: Single");
                log.info("Mode: Single");
                processor = new SingleModeProcessor();
            }
        }
        return processor;
    }

    private class SingleModeProcessor implements Processor {
        @Override
        public void process(Request request) {
            try {
                IndexResponse indexResponse = getClient().index(request, RequestOptions.DEFAULT);
                if (indexResponse.getResult().equals(DocWriteResponse.Result.CREATED)) {
                    request.getRecord().ack();
                } else {
                    request.getRecord().fail();
                }
            } catch (final IOException ex) {
                log.error("Failed to process record with sequence [" + request.getRecord().getRecordSequence() + "}");
                request.getRecord().fail();
            }
        }

        @Override
        public void close() throws Exception {
            /* do nothing */
        }
    }

    private class BulkModeProcessor implements Processor {
        private BulkProcessor bulkProcessor;

        @Override
        public void process(Request request) {
            getBulkProcessor().add(request);
        }

        @Override
        public void close() throws Exception {
            bulkProcessor.awaitClose(elasticSearchConfig.getBulkAwaitClose(), TimeUnit.MILLISECONDS);
        }

        private BulkProcessor getBulkProcessor() {
            if (bulkProcessor == null) {
                BulkProcessor.Listener listener = new BulkProcessor.Listener() {
                    @Override
                    public void beforeBulk(long executionId, BulkRequest bulkRequest) {
                        int numberOfActions = bulkRequest.numberOfActions();
                        log.info("Bulk [{}] - Executing {} requests",
                            executionId, numberOfActions);
                    }

                    @Override
                    public void afterBulk(long executionId, BulkRequest bulkRequest, BulkResponse bulkResponse) {
                        BulkItemResponse[] responseItems = bulkResponse.getItems();
                        int numberOfActions = bulkRequest.numberOfActions();
                        int numberOfResponses = bulkResponse.getItems().length;
                        int numberOfFailures = 0;

                        for(int i = 0; i < responseItems.length; ++i) {
                            Request request = ((Request) bulkRequest.requests().get(i));
                            Record record = request.getRecord();
                            BulkItemResponse respItem = responseItems[i];

                            try {
                                if (!respItem.isFailed()) {
                                    record.ack();
                                } else {
                                    String e = respItem.getFailureMessage();
                                    log.warn("Bulk [{}] - Item [{}} failed request: {}", executionId, i, e);
                                    record.fail();
                                    ++numberOfFailures;
                                }
                            } catch (Exception e) {
                                ++numberOfFailures;
                                log.warn("Bulk [{}] - Item [{}} failed to ACK or Fail:", executionId, i, e);
                                record.fail();
                            }
                        }

                        if (numberOfActions != numberOfResponses) {
                            log.warn("Bulk [{}] - Got {} responses of {} requests.", executionId, numberOfActions, numberOfResponses);
                        }

                        if (numberOfFailures > 0) {
                            log.warn("Bulk [{}] - Completed {} actions with {} failures in {} milliseconds, ingest took {} milliseconds",
                                executionId, numberOfFailures, numberOfActions,
                                bulkResponse.getTook().getMillis(), bulkResponse.getIngestTook().getMillis());
                        } else {
                            log.info("Bulk [{}] - Completed {} actions in {} milliseconds, ingest took {} milliseconds",
                                executionId, numberOfActions,
                                bulkResponse.getTook().getMillis(), bulkResponse.getIngestTook().getMillis());
                        }
                    }

                    @Override
                    public void afterBulk(long executionId, BulkRequest bulkRequest, Throwable failure) {
                        log.error("Bulk [{}] - Failed to execute bulk", executionId, failure);
                    }
                };

                BulkProcessor.Builder bulkProcessorBuilder = BulkProcessor.builder(
                    (request, bulkListener) ->
                        client.bulkAsync(request, RequestOptions.DEFAULT, bulkListener),
                    listener
                );

                bulkProcessorBuilder
                    .setConcurrentRequests(elasticSearchConfig.getBulkConcurrentRequests())
                    .setBulkActions(elasticSearchConfig.getBulkActions())
                    .setBulkSize(new ByteSizeValue(elasticSearchConfig.getBulkSize(), ByteSizeUnit.MB))
                    .setFlushInterval(TimeValue.timeValueMillis(elasticSearchConfig.getBulkFlushInterval()))
                    .setBackoffPolicy(BackoffPolicy.exponentialBackoff(
                        TimeValue.timeValueMillis(
                            elasticSearchConfig.getBulkBackoffInterval()),
                        elasticSearchConfig.getBulkBackoffRetries()
                    ));

                bulkProcessor = bulkProcessorBuilder.build();
            }
            return bulkProcessor;
        }
    }
}
