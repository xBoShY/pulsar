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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Map;
import lombok.Data;
import lombok.experimental.Accessors;
import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.io.core.annotations.FieldDoc;

/**
 * Configuration class for the ElasticSearch Sink Connector.
 */
@Data
@Accessors(chain = true)
public class ElasticSearchConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @FieldDoc(
        required = true,
        defaultValue = "",
        help = "The url of elastic search cluster that the connector connects to"
    )
    private String elasticSearchUrl;

    @FieldDoc(
        required = true,
        defaultValue = "",
        help = "The index name that the connector writes messages to"
    )
    private String indexName;

    @FieldDoc(
        required = false,
        defaultValue = "1",
        help = "The number of shards of the index"
    )
    private int indexNumberOfShards = 1;

    @FieldDoc(
        required = false,
        defaultValue = "1",
        help = "The number of replicas of the index"
    )
    private int indexNumberOfReplicas = 1;

    @FieldDoc(
        required = false,
        defaultValue = "",
        sensitive = true,
        help = "The username used by the connector to connect to the elastic search cluster. If username is set, a password should also be provided."
    )
    private String username;

    @FieldDoc(
        required = false,
        defaultValue = "",
        sensitive = true,
        help = "The password used by the connector to connect to the elastic search cluster. If password is set, a username should also be provided"
    )
    private String password;

    @FieldDoc(
        required = false,
        defaultValue = "single",
        sensitive = false,
        help = ""
    )
    private String mode = "single";

    @FieldDoc(
        required = false,
        defaultValue = "0",
        help = ""
    )
    private int bulkConcurrentRequests = 0;

    @FieldDoc(
        required = false,
        defaultValue = "500",
        help = ""
    )
    private int bulkActions = 500;

    @FieldDoc(
        required = false,
        defaultValue = "4",
        help = ""
    )
    private int bulkSize = 4;

    @FieldDoc(
        required = false,
        defaultValue = "2000",
        help = ""
    )
    private int bulkFlushInterval = 2000;

    @FieldDoc(
        required = false,
        defaultValue = "1000",
        help = ""
    )
    private int bulkBackoffInterval = 1000;

    @FieldDoc(
        required = false,
        defaultValue = "3",
        help = ""
    )
    private int bulkBackoffRetries = 3;

    @FieldDoc(
        required = false,
        defaultValue = "3",
        help = ""
    )
    private int bulkAwaitClose = 3;

    public static ElasticSearchConfig load(String yamlFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(new File(yamlFile), ElasticSearchConfig.class);
    }

    public static ElasticSearchConfig load(Map<String, Object> map) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(new ObjectMapper().writeValueAsString(map), ElasticSearchConfig.class);
    }

    public void validate() {
        if (StringUtils.isEmpty(elasticSearchUrl) || StringUtils.isEmpty(indexName)) {
            throw new IllegalArgumentException("Required property not set.");
        }

        if ((StringUtils.isNotEmpty(username) && StringUtils.isEmpty(password))
           || (StringUtils.isEmpty(username) && StringUtils.isNotEmpty(password))) {
            throw new IllegalArgumentException("Values for both Username & password are required.");
        }

        if (indexNumberOfShards < 1) {
            throw new IllegalArgumentException("indexNumberOfShards must be a strictly positive integer");
        }

        if (indexNumberOfReplicas < 0) {
            throw new IllegalArgumentException("indexNumberOfReplicas must be a positive integer");
        }

        if ((
            StringUtils.isNotEmpty(mode)
                && !StringUtils.equalsIgnoreCase(mode, "single")
                && !StringUtils.equalsIgnoreCase(mode, "bulk"))
            || (StringUtils.isEmpty(mode))) {
            throw new IllegalArgumentException("Invalid Mode [" + mode + "]. Must be [single,bulk].");
        }

        if (StringUtils.equalsIgnoreCase(mode, "bulk")) {
            if (bulkConcurrentRequests < 0) {
                throw new IllegalArgumentException("");
            }

            if (bulkActions < 0) {
                throw new IllegalArgumentException("");
            }

            if (bulkSize < 0) {
                throw new IllegalArgumentException("");
            }

            if (bulkFlushInterval < 0) {
                throw new IllegalArgumentException("");
            }

            if (bulkBackoffInterval < 0) {
                throw new IllegalArgumentException("");
            }

            if (bulkBackoffRetries < 0) {
                throw new IllegalArgumentException("");
            }

            if (bulkAwaitClose < 0) {
                throw new IllegalArgumentException("");
            }
        }
    }
}
