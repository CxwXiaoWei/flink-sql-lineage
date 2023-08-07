/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hw.lineage.client;

import com.google.common.collect.ImmutableMap;
import com.hw.lineage.common.model.LineageResult;

import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

/**
 * @description: LineageClientTest
 * @author: HamaWhite
 */
public class LineageClientTest {

    private static final Logger LOG = LoggerFactory.getLogger(LineageClientTest.class);

    private static final String[] PLUGIN_CODES = {"flink1.14.x", "flink1.16.x"};

    private static final String catalogName = "memory_catalog";

    private static final String database = "lineage_db";

    private static LineageClient client;

    @BeforeClass
    public static void setup() {
        client = new LineageClient("target/plugins");

        Map<String, String> propertiesMap = ImmutableMap.of(
                "type", "generic_in_memory",
                "default-database", database);

        Stream.of(PLUGIN_CODES).forEach(pluginCode -> {
            client.createCatalog(pluginCode, catalogName, propertiesMap);

            client.useCatalog(pluginCode, catalogName);
            // create mysql cdc table ods_mysql_users
            createTableOfOdsMysqlUsers(pluginCode);
            // create hudi sink table dwd_hudi_users
            createTableOfDwdHudiUsers(pluginCode);
        });
    }

    @Test
    public void testInsertSelect() {
        Stream.of(PLUGIN_CODES).forEach(this::testInsertSelect);
    }

    private void testInsertSelect(String pluginCode) {
        String sql = "INSERT INTO dwd_hudi_users " +
                "SELECT " +
                "   id ," +
                "   name ," +
                "   name as company_name ," +
                "   birthday ," +
                "   ts ," +
                "   DATE_FORMAT(birthday, 'yyyyMMdd') " +
                "FROM" +
                "   ods_mysql_users";

        String[][] expectedArray = {
                {"ods_mysql_users", "id", "dwd_hudi_users", "id"},
                {"ods_mysql_users", "name", "dwd_hudi_users", "name"},
                {"ods_mysql_users", "name", "dwd_hudi_users", "company_name"},
                {"ods_mysql_users", "birthday", "dwd_hudi_users", "birthday"},
                {"ods_mysql_users", "ts", "dwd_hudi_users", "ts"},
                {"ods_mysql_users", "birthday", "dwd_hudi_users", "partition", "DATE_FORMAT(birthday, 'yyyyMMdd')"}
        };

        analyzeLineage(pluginCode, sql, expectedArray);
    }

    private void analyzeLineage(String pluginCode, String sql, String[][] expectedArray) {
        List<LineageResult> actualList = client.analyzeLineage(pluginCode, catalogName, database, sql);
        LOG.info("Linage Result: ");
        actualList.forEach(e -> LOG.info(e.toString()));

        List<LineageResult> expectedList = LineageResult.buildResult(catalogName, database, expectedArray);
        assertEquals(expectedList, actualList);
    }

    /**
     * Create mysql cdc table ods_mysql_users
     */
    private static void createTableOfOdsMysqlUsers(String pluginCode) {
        client.execute(pluginCode, "DROP TABLE IF EXISTS ods_mysql_users ");

        client.execute(pluginCode, "CREATE TABLE IF NOT EXISTS ods_mysql_users (" +
                "       id                  BIGINT PRIMARY KEY NOT ENFORCED ," +
                "       name                STRING                          ," +
                "       birthday            TIMESTAMP(3)                    ," +
                "       ts                  TIMESTAMP(3)                    ," +
                "       proc_time as proctime()                              " +
                ") WITH ( " +
                "       'connector' = 'mysql-cdc'            ," +
                "       'hostname'  = '127.0.0.1'       ," +
                "       'port'      = '3306'                 ," +
                "       'username'  = 'root'                 ," +
                "       'password'  = 'xxx'          ," +
                "       'server-time-zone' = 'Asia/Shanghai' ," +
                "       'database-name' = 'demo'             ," +
                "       'table-name'    = 'users' " +
                ")");
    }

    /**
     * Create Hudi sink table dwd_hudi_users
     */
    private static void createTableOfDwdHudiUsers(String pluginCode) {
        client.execute(pluginCode, "DROP TABLE IF EXISTS dwd_hudi_users");

        client.execute(pluginCode, "CREATE TABLE IF NOT EXISTS  dwd_hudi_users ( " +
                "       id                  BIGINT PRIMARY KEY NOT ENFORCED ," +
                "       name                STRING                          ," +
                "       company_name        STRING                          ," +
                "       birthday            TIMESTAMP(3)                    ," +
                "       ts                  TIMESTAMP(3)                    ," +
                "        `partition`        VARCHAR(20)                      " +
                ") PARTITIONED BY (`partition`) WITH ( " +
                "       'connector' = 'hudi'                                    ," +
                "       'table.type' = 'COPY_ON_WRITE'                          ," +
                "       'read.streaming.enabled' = 'true'                       ," +
                "       'read.streaming.check-interval' = '1'                    " +
                ")");
    }

    @Test
    public void testConvertProperties() {
        Map<String, String> propertiesMap = ImmutableMap.of(
                "type", "jdbc",
                "default-database", "lineage_catalog",
                "username", "root",
                "password", "root@123456",
                "base-url", "jdbc:mysql://127.0.0.1:3306");
        String properties = propertiesMap.entrySet()
                .stream()
                .map(entry -> String.format("'%s'='%s'", entry.getKey(), entry.getValue()))
                .collect(Collectors.joining(","));

        assertThat(properties, is(
                "'type'='jdbc','default-database'='lineage_catalog','username'='root','password'='root@123456','base-url'='jdbc:mysql://127.0.0.1:3306'"));
    }

}