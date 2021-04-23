/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.monitoring.job

import com.tencent.devops.common.api.util.timestampmilli
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.notify.enums.EnumEmailFormat
import com.tencent.devops.common.redis.RedisLock
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.common.service.Profile
import com.tencent.devops.monitoring.client.InfluxdbClient
import com.tencent.devops.monitoring.dao.SlaDailyDao
import com.tencent.devops.monitoring.util.EmailModuleData
import com.tencent.devops.monitoring.util.EmailUtil
import com.tencent.devops.notify.api.service.ServiceNotifyResource
import com.tencent.devops.notify.pojo.EmailNotifyMessage
import org.apache.commons.lang3.time.DateFormatUtils
import org.apache.commons.lang3.tuple.MutablePair
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.client.RestHighLevelClient
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.search.builder.SearchSourceBuilder
import org.influxdb.dto.QueryResult
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.context.config.annotation.RefreshScope
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

@Component
@RefreshScope
class MonitorNotifyJob @Autowired constructor(
    private val client: Client,
    private val influxdbClient: InfluxdbClient,
    private val slaDailyDao: SlaDailyDao,
    private val dslContext: DSLContext,
    private val restHighLevelClient: RestHighLevelClient,
    private val profile: Profile,
    private val redisOperation: RedisOperation
) {

    @Value("\${sla.receivers:#{null}}")
    private var receivers: String? = null

    @Value("\${sla.title:#{null}}")
    private var title: String? = null

    @Value("\${sla.url.detail.atom:#{null}}")
    private var atomDetailUrl: String? = null

    @Value("\${sla.url.detail.dispatch:#{null}}")
    private var dispatchDetailUrl: String? = null

    @Value("\${sla.url.detail.userStatus:#{null}}")
    private var userStatusDetailUrl: String? = null

    @Value("\${sla.url.detail.commitCheck:#{null}}")
    private var commitCheckDetailUrl: String? = null

    @Value("\${sla.url.detail.codecc:#{null}}")
    private var codeccDetailUrl: String? = null

    @Value("\${sla.url.observable.atom:#{null}}")
    private var atomObservableUrl: String? = null

    @Value("\${sla.url.observable.dispatch:#{null}}")
    private var dispatchObservableUrl: String? = null

    @Value("\${sla.url.observable.userStatus:#{null}}")
    private var userStatusObservableUrl: String? = null

    @Value("\${sla.url.observable.commitCheck:#{null}}")
    private var commitCheckObservableUrl: String? = null

    @Value("\${sla.url.observable.codecc:#{null}}")
    private var codeccObservableUrl: String? = null

    /**
     * 每天发送日报
     */
    @Scheduled(cron = "0 0 10 * * ?")
    fun notifyDaily() {
        if (profile.isProd() && !profile.isProdGray()) {
            logger.info("profile is prod , no start")
            return
        }

        if (illegalConfig()) {
            logger.info("some params is null , notifyDaily no start")
            return
        }

        val redisLock = RedisLock(redisOperation, "slaDailyEmail", 60L)
        try {
            logger.info("MonitorNotifyJob , notifyDaily start")
            val lockSuccess = redisLock.tryLock()
            if (lockSuccess) {
                doNotify()
                logger.info("MonitorNotifyJob , notifyDaily finish")
            } else {
                logger.info("SLA Daily Email is running")
            }
        } catch (e: Throwable) {
            logger.error("SLA Daily Email error:", e)
        }
    }

    private fun illegalConfig() =
        null == receivers || null == title || null == atomDetailUrl || null == dispatchDetailUrl ||
                null == userStatusDetailUrl || null == codeccDetailUrl || null == atomObservableUrl ||
                null == dispatchObservableUrl || null == userStatusObservableUrl || null == codeccObservableUrl

    private fun doNotify() {
        val yesterday = LocalDateTime.now().minusDays(1)
        val startTime = yesterday.withHour(0).withMinute(0).withSecond(0).timestampmilli()
        val endTime = yesterday.withHour(23).withMinute(59).withSecond(59).timestampmilli()

        val moduleDataList = listOf(
            gatewayStatus(startTime, endTime),
            atomMonitor(startTime, endTime),
            dispatchStatus(startTime, endTime),
            userStatus(startTime, endTime),
            commitCheck(startTime, endTime),
            codecc(startTime, endTime)
        )

        // 发送邮件
        val message = EmailNotifyMessage()
        message.addAllReceivers(receivers!!.split(",").toHashSet())
        message.title = title as String
        message.body = EmailUtil.getEmailBody(startTime, endTime, moduleDataList)
        message.format = EnumEmailFormat.HTML
        client.get(ServiceNotifyResource::class).sendEmailNotify(message)

        // 落库
        val startLocalTime = Instant.ofEpochMilli(startTime).atZone(ZoneOffset.ofHours(8)).toLocalDateTime()
        val endLocalTime = Instant.ofEpochMilli(endTime).atZone(ZoneOffset.ofHours(8)).toLocalDateTime()
        moduleDataList.forEach { m ->
            m.rowList.forEach { l ->
                l.run {
                    slaDailyDao.insert(dslContext, m.module, l.first, l.second, startLocalTime, endLocalTime)
                }
            }
        }
    }

    private fun commitCheck(startTime: Long, endTime: Long): EmailModuleData {
        try {
            val sql =
                "SELECT sum(commit_total_count),sum(commit_success_count) FROM CommitCheck_success_rat_count " +
                        "WHERE time>${startTime}000000 AND time<${endTime}000000"
            val queryResult = influxdbClient.select(sql)

            val rowList = mutableListOf<Triple<String, Double, String>>()
            if (null != queryResult && !queryResult.hasError()) {
                putCommitCheckRowList(queryResult, rowList, startTime, endTime)
            } else {
                logger.error("commitCheck , get map error , errorMsg:${queryResult?.error}")
            }

            return EmailModuleData("工蜂回写统计", rowList, getObservableUrl(startTime, endTime, Module.COMMIT_CHECK))
        } catch (e: Throwable) {
            logger.error("commitCheck", e)
            return EmailModuleData("工蜂回写统计", emptyList(), getObservableUrl(startTime, endTime, Module.COMMIT_CHECK))
        }
    }

    private fun putCommitCheckRowList(
        queryResult: QueryResult,
        rowList: MutableList<Triple<String, Double, String>>,
        startTime: Long,
        endTime: Long
    ) {
        queryResult.results.forEach { result ->
            result.series?.forEach { serie ->
                val countAny = serie.values[0][1]
                val successAny = serie.values[0][2]
                val count = if (countAny is Number) countAny.toInt() else 1
                val success = if (successAny is Number) successAny.toInt() else 0
                rowList.add(
                    Triple(
                        "CommitCheck",
                        success * 100.0 / count,
                        getDetailUrl(startTime, endTime, Module.COMMIT_CHECK)
                    )
                )
            }
        }
    }

    fun gatewayStatus(startTime: Long, endTime: Long): EmailModuleData {
        try {
            val rowList = mutableListOf<Triple<String, Double, String>>()
            for (name in arrayOf(
                "process",
                "dispatch",
                "openapi",
                "artifactory",
                "websocket",
                "store",
                "log",
                "environment"
            )) {
                val errorCount = getHits(startTime, name, true)
                val totalCount = getHits(startTime, name).coerceAtLeast(1)
                rowList.add(
                    Triple(
                        name,
                        100 - (errorCount * 100.0 / totalCount),
                        getDetailUrl(startTime, endTime, Module.GATEWAY, name)
                    )
                )
            }

            return EmailModuleData(
                "网关统计",
                rowList.asSequence().sortedBy { it.second }.toList(),
                getObservableUrl(startTime, endTime, Module.GATEWAY)
            )
        } catch (e: Throwable) {
            logger.error("gatewayStatus", e)
            return EmailModuleData(
                "网关统计",
                emptyList(),
                getObservableUrl(startTime, endTime, Module.GATEWAY)
            )
        }
    }

    fun userStatus(startTime: Long, endTime: Long): EmailModuleData {
        try {
            val sql =
                "SELECT sum(user_total_count),sum(user_success_count) FROM UsersStatus_success_rat_count " +
                        "WHERE time>${startTime}000000 AND time<${endTime}000000"
            val queryResult = influxdbClient.select(sql)

            val rowList = mutableListOf<Triple<String, Double, String>>()
            if (null != queryResult && !queryResult.hasError()) {
                putUserStatusRowList(queryResult, rowList, startTime, endTime)
            } else {
                logger.error("userStatus , get map error , errorMsg:${queryResult?.error}")
            }

            return EmailModuleData("用户登录统计", rowList, getObservableUrl(startTime, endTime, Module.USER_STATUS))
        } catch (e: Throwable) {
            logger.error("userStatus", e)
            return EmailModuleData("用户登录统计", emptyList(), getObservableUrl(startTime, endTime, Module.USER_STATUS))
        }
    }

    private fun putUserStatusRowList(
        queryResult: QueryResult,
        rowList: MutableList<Triple<String, Double, String>>,
        startTime: Long,
        endTime: Long
    ) {
        queryResult.results.forEach { result ->
            result.series?.forEach { serie ->
                val countAny = serie.values[0][1]
                val successAny = serie.values[0][2]
                val success = if (successAny is Number) successAny.toInt() else 0
                rowList.add(
                    Triple(
                        "userStatus",
                        success * 100.0 / if (countAny is Number) countAny.toInt() else 1,
                        getDetailUrl(startTime, endTime, Module.USER_STATUS)
                    )
                )
            }
        }
    }

    fun dispatchStatus(startTime: Long, endTime: Long): EmailModuleData {
        try {
            val sql =
                "SELECT sum(devcloud_total_count),sum(devcloud_success_count) FROM DispatchStatus_success_rat_count " +
                        "WHERE time>${startTime}000000 AND time<${endTime}000000 GROUP BY buildType"
            val queryResult = influxdbClient.select(sql)

            val rowList = mutableListOf<Triple<String, Double, String>>()
            if (null != queryResult && !queryResult.hasError()) {
                putDispatchStatusRowList(queryResult, rowList, startTime, endTime)
            } else {
                logger.error("dispatchStatus , get map error , errorMsg:${queryResult?.error}")
            }

            return EmailModuleData(
                "公共构建机统计",
                rowList.asSequence().sortedBy { it.second }.toList(),
                getObservableUrl(startTime, endTime, Module.DISPATCH)
            )
        } catch (e: Throwable) {
            logger.error("dispatchStatus", e)
            return EmailModuleData(
                "公共构建机统计",
                emptyList(),
                getObservableUrl(startTime, endTime, Module.DISPATCH)
            )
        }
    }

    private fun putDispatchStatusRowList(
        queryResult: QueryResult,
        rowList: MutableList<Triple<String, Double, String>>,
        startTime: Long,
        endTime: Long
    ) {
        queryResult.results.forEach { result ->
            result.series?.forEach { serie ->
                val countAny = serie.values[0][1]
                val successAny = serie.values[0][2]
                val count = if (countAny is Number) countAny.toInt() else 1
                val success = if (successAny is Number) successAny.toInt() else 0
                val name = serie.tags["buildType"] ?: "Unknown"
                rowList.add(
                    Triple(
                        name,
                        success * 100.0 / count,
                        getDetailUrl(startTime, endTime, Module.DISPATCH, name)
                    )
                )
            }
        }
    }

    @SuppressWarnings("ComplexMethod", "NestedBlockDepth")
    fun atomMonitor(startTime: Long, endTime: Long): EmailModuleData {
        try {
            val sql =
                "SELECT sum(total_count),sum(success_count),sum(CODE_GIT_total_count),sum(CODE_GIT_success_count)" +
                        ",sum(UploadArtifactory_total_count),sum(UploadArtifactory_success_count)," +
                        "sum(linuxscript_total_count),sum(linuxscript_success_count) " +
                        "FROM AtomMonitorData_success_rat_count WHERE time>${startTime}000000 AND time<${endTime}000000"
            val queryResult = influxdbClient.select(sql)

            var totalCount = 1
            var totalSuccess = 0
            var gitCount = 1
            var gitSuccess = 0
            var artiCount = 1
            var artiSuccess = 0
            var shCount = 1
            var shSuccess = 0

            if (null != queryResult && !queryResult.hasError()) {
                queryResult.results.forEach { result ->
                    result.series?.forEach { serie ->
                        totalCount = serie.values[0][1].let { if (it is Number) it.toInt() else 1 }
                        totalSuccess = serie.values[0][2].let { if (it is Number) it.toInt() else 0 }
                        gitCount = serie.values[0][3].let { if (it is Number) it.toInt() else 1 }
                        gitSuccess = serie.values[0][4].let { if (it is Number) it.toInt() else 0 }
                        artiCount = serie.values[0][5].let { if (it is Number) it.toInt() else 1 }
                        artiSuccess = serie.values[0][6].let { if (it is Number) it.toInt() else 0 }
                        shCount = serie.values[0][7].let { if (it is Number) it.toInt() else 1 }
                        shSuccess = serie.values[0][8].let { if (it is Number) it.toInt() else 0 }
                    }
                }
            } else {
                logger.error("atomMonitor , get map error , errorMsg:${queryResult?.error}")
            }

            val rowList = mutableListOf(
                Triple("所有插件", totalSuccess * 100.0 / totalCount, getDetailUrl(startTime, endTime, Module.ATOM)),
                Triple(
                    "Git插件",
                    gitSuccess * 100.0 / gitCount,
                    getDetailUrl(startTime, endTime, Module.ATOM, "CODE_GIT")
                ),
                Triple(
                    "artifactory插件",
                    artiSuccess * 100.0 / artiCount,
                    getDetailUrl(startTime, endTime, Module.ATOM, "UploadArtifactory")
                ),
                Triple(
                    "linuxScript插件",
                    shSuccess * 100.0 / shCount,
                    getDetailUrl(startTime, endTime, Module.ATOM, "linuxScript")
                )
            )

            return EmailModuleData(
                "核心插件统计",
                rowList.asSequence().sortedBy { it.second }.toList(),
                getObservableUrl(startTime, endTime, Module.ATOM)
            )
        } catch (e: Throwable) {
            logger.error("atomMonitor", e)
            return EmailModuleData(
                "核心插件统计",
                emptyList(),
                getObservableUrl(startTime, endTime, Module.ATOM)
            )
        }
    }

    fun codecc(startTime: Long, endTime: Long): EmailModuleData {
        try {
            val successSql =
                "SELECT SUM(total_count)  FROM CodeccMonitor_reduce " +
                        "WHERE time>${startTime}000000 AND time<${endTime}000000 AND errorCode='0' GROUP BY toolName"
            val errorSql =
                "SELECT SUM(total_count)  FROM CodeccMonitor_reduce " +
                        "WHERE time>${startTime}000000 AND time<${endTime}000000 AND errorCode!='0' GROUP BY toolName"

            val successMap = getCodeCCMap(successSql)
            val errorMap = getCodeCCMap(errorSql)

            val reduceMap = HashMap<String/*toolName*/, MutablePair<Int/*success*/, Int/*error*/>>()

            for ((k, v) in successMap) {
                reduceMap[k] = MutablePair(v, 0)
            }
            for ((k, v) in errorMap) {
                if (reduceMap.containsKey(k)) {
                    reduceMap[k]?.right = v
                } else {
                    reduceMap[k] = MutablePair(0, v)
                }
            }

            val rowList =
                reduceMap.asSequence().sortedBy { it.value.left * 100.0 / it.value.right }.map {
                    Triple(
                        it.key,
                        it.value.left * 100.0 / (it.value.left + it.value.right),
                        getDetailUrl(startTime, endTime, Module.CODECC, it.key)
                    )
                }.toList()

            return EmailModuleData("CodeCC工具统计", rowList, getObservableUrl(startTime, endTime, Module.CODECC))
        } catch (e: Throwable) {
            logger.error("codecc", e)
            return EmailModuleData("CodeCC工具统计", emptyList(), getObservableUrl(startTime, endTime, Module.CODECC))
        }
    }

    @SuppressWarnings("NestedBlockDepth")
    private fun getCodeCCMap(sql: String): HashMap<String, Int> {
        val queryResult = influxdbClient.select(sql)
        val codeCCMap = HashMap<String/*toolName*/, Int/*count*/>()
        if (null != queryResult && !queryResult.hasError()) {
            queryResult.results.forEach { result ->
                result.series?.forEach { serie ->
                    serie.run {
                        val key = tags["toolName"]
                        if (null != key) {
                            val value = if (values.size > 0 && values[0].size > 1) values[0][1] else 0
                            codeCCMap[key] = if (value is Number) value.toInt() else 0
                        }
                    }
                }
            }
        } else {
            logger.error("codecc , get map error , errorMsg:${queryResult?.error}")
        }
        return codeCCMap
    }

    private fun getHits(startTime: Long, name: String, error: Boolean = false): Long {
        val sourceBuilder = SearchSourceBuilder()
        val queryStringQuery = QueryBuilders.queryStringQuery(
            """
                    path:"/data/bkci/logs/$name/access_log.log" 
                    ${if (error) " AND status:[500 TO *] " else ""}
                """.trimIndent()
        )
        val query =
            QueryBuilders.boolQuery()
                .filter(
                    queryStringQuery
                )
        sourceBuilder.query(query).size(1)

        val searchRequest = SearchRequest()
        searchRequest.indices("v2_9_bklog_prod_ci_service_access_${DateFormatUtils.format(startTime, "yyyyMMdd")}*")
        searchRequest.source(sourceBuilder)
        val hits = restHighLevelClient.search(searchRequest).hits.getTotalHits()
        logger.info("apiStatus:$name , hits:$hits")
        return hits
    }

    private fun getObservableUrl(startTime: Long, endTime: Long, module: Module): String {
        return when (module) {
            Module.GATEWAY -> "http://opdata.devops.oa.com/" +
                    "d/sL8BLj7Gk/v2-wang-guan-accessjian-kong?orgId=1&from=$startTime&to=$endTime"
            Module.CODECC -> "$codeccObservableUrl?from=$startTime&to=$endTime"
            Module.ATOM -> "$atomObservableUrl?from=$startTime&to=$endTime"
            Module.DISPATCH -> "$dispatchObservableUrl?from=$startTime&to=$endTime"
            Module.USER_STATUS -> "$userStatusObservableUrl?from=$startTime&to=$endTime"
            Module.COMMIT_CHECK -> "$commitCheckObservableUrl?from=$startTime&to=$endTime"
        }
    }

    private fun getDetailUrl(startTime: Long, endTime: Long, module: Module, name: String = ""): String {
        return when (module) {
            Module.GATEWAY -> gatewayDetailUrl(startTime, endTime, name)
            Module.ATOM -> "$atomDetailUrl?var-atomCode=$name&from=$startTime&to=$endTime"
            Module.DISPATCH -> "$dispatchDetailUrl?var-buildType=$name&from=$startTime&to=$endTime"
            Module.USER_STATUS -> "$userStatusDetailUrl?from=$startTime&to=$endTime"
            Module.COMMIT_CHECK -> "$commitCheckDetailUrl?from=$startTime&to=$endTime"
            Module.CODECC -> "$codeccDetailUrl?var-toolName=$name&from=$startTime&to=$endTime"
        }
    }

    private fun gatewayDetailUrl(startTime: Long, endTime: Long, name: String): String {
        val startTimeStr = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(startTime),
            ZoneId.ofOffset("UTC", ZoneOffset.UTC)
        ).toString()
        val endTimeStr = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(endTime),
            ZoneId.ofOffset("UTC", ZoneOffset.UTC)
        ).toString()
        return """
            http://logs.ms.devops.oa.com/app/kibana#/discover?_g=(refreshInterval:(pause:!t,value:0),
            time:(from:'${startTimeStr}Z',mode:absolute,to:'${endTimeStr}Z'))&
            _a=(columns:!(_source),index:'4b38ef10-9da1-11eb-8559-712c276f42f2',
            interval:auto,query:(language:lucene,query:'path:%22%2Fdata%2Fbkci%2Flogs%2F$name%2Faccess_log.log
            %22%20AND%20status:%5B500%20TO%20*%5D'),sort:!(time,desc))
        """.trimIndent().replace("\n", "")
    }

    enum class Module {
        GATEWAY,
        ATOM,
        DISPATCH,
        USER_STATUS,
        COMMIT_CHECK,
        CODECC;
    }

    companion object {
        private val logger = LoggerFactory.getLogger(MonitorNotifyJob::class.java)
    }
}
