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
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.tencent.devops.lambda.service

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.tencent.devops.common.api.enums.RepositoryType
import com.tencent.devops.common.api.exception.InvalidParamException
import com.tencent.devops.common.api.pojo.ErrorType
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.api.util.timestampmilli
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.event.pojo.pipeline.PipelineBuildFinishBroadCastEvent
import com.tencent.devops.common.event.pojo.pipeline.PipelineBuildTaskFinishBroadCastEvent
import com.tencent.devops.common.kafka.KafkaClient
import com.tencent.devops.common.kafka.KafkaTopic
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.pipeline.enums.ChannelCode
import com.tencent.devops.lambda.LambdaMessageCode.ERROR_LAMBDA_PROJECT_NOT_EXIST
import com.tencent.devops.lambda.dao.BuildContainerDao
import com.tencent.devops.lambda.dao.BuildTaskDao
import com.tencent.devops.lambda.dao.LambdaPipelineBuildDao
import com.tencent.devops.lambda.dao.PipelineResDao
import com.tencent.devops.lambda.dao.PipelineTemplateDao
import com.tencent.devops.lambda.pojo.BuildData
import com.tencent.devops.lambda.pojo.DataPlatJobDetail
import com.tencent.devops.lambda.pojo.DataPlatTaskDetail
import com.tencent.devops.lambda.pojo.ElementData
import com.tencent.devops.lambda.pojo.ProjectOrganize
import com.tencent.devops.lambda.storage.ESService
import com.tencent.devops.model.process.tables.records.TPipelineBuildHistoryRecord
import com.tencent.devops.model.process.tables.records.TPipelineBuildTaskRecord
import com.tencent.devops.process.engine.pojo.BuildInfo
import com.tencent.devops.project.api.service.ServiceProjectResource
import com.tencent.devops.repository.api.ServiceRepositoryResource
import org.jooq.DSLContext
import org.json.simple.JSONObject
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

@Service
class PipelineBuildService @Autowired constructor(
    private val client: Client,
    private val dslContext: DSLContext,
    private val lambdaPipelineBuildDao: LambdaPipelineBuildDao,
    private val pipelineResDao: PipelineResDao,
    private val pipelineTemplateDao: PipelineTemplateDao,
    private val buildTaskDao: BuildTaskDao,
    private val buildContainerDao: BuildContainerDao,
    private val esService: ESService,
    private val kafkaClient: KafkaClient
) {

    fun onBuildFinish(event: PipelineBuildFinishBroadCastEvent) {
        val info = getBuildInfo(event.buildId)
        if (info == null) {
            logger.warn("[${event.projectId}|${event.pipelineId}|${event.buildId}] The build info is not exist")
            return
        }
        val model = getModel(info.pipelineId, info.version)
        if (model == null) {
            logger.warn("[${event.projectId}|${event.pipelineId}|${event.buildId}] Fail to get the pipeline model")
            return
        }

        val projectInfo = projectCache.get(info.projectId)

        val data = BuildData(
            projectId = info.projectId,
            pipelineId = info.pipelineId,
            buildId = info.buildId,
            userId = info.startUser,
            status = info.status.name,
            trigger = info.trigger,
            beginTime = info.startTime ?: 0,
            endTime = info.endTime ?: 0,
            buildNum = info.buildNum,
            templateId = templateCache.get(info.pipelineId),
            bgName = projectInfo.bgName,
            deptName = projectInfo.deptName,
            centerName = projectInfo.centerName,
            model = model,
            errorType = event.errorType,
            errorCode = event.errorCode,
            errorMsg = event.errorMsg
        )
        esService.build(data)
    }

    fun onBuildTaskFinish(event: PipelineBuildTaskFinishBroadCastEvent) {
        val task = buildTaskDao.getTask(dslContext, event.buildId, event.taskId)
        if (task == null) {
            logger.warn("[${event.projectId}|${event.pipelineId}|${event.buildId}|${event.taskId}] Fail to get the build task")
            return
        }
        pushElementData2Es(event, task)
        pushGitTaskInfo(event, task)
        pushTaskDetail(event, task)
    }

    private fun pushElementData2Es(event: PipelineBuildTaskFinishBroadCastEvent, task: TPipelineBuildTaskRecord) {
        val data = ElementData(
            projectId = event.projectId,
            pipelineId = event.pipelineId,
            buildId = event.buildId,
            elementId = event.taskId,
            elementName = task.taskName ?: "",
            status = BuildStatus.values()[task.status ?: 0].name,
            beginTime = task.startTime?.timestampmilli() ?: 0,
            endTime = task.endTime?.timestampmilli() ?: 0,
            type = task.taskType ?: "",
            atomCode = task.taskAtom ?: "",
            errorType = event.errorType,
            errorCode = event.errorCode,
            errorMsg = event.errorMsg
        )
        try {
            esService.buildElement(data)
        } catch (e: Exception) {
            logger.error("Push elementData to es error, buildId: ${event.buildId}, taskId: ${event.taskId}", e)
        }
    }

    private fun pushTaskDetail(event: PipelineBuildTaskFinishBroadCastEvent, task: TPipelineBuildTaskRecord) {
        try {
            val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            val startTime = task.startTime?.timestampmilli() ?: 0
            val endTime = task.endTime?.timestampmilli() ?: 0
            val taskAtom = task.taskAtom
            val taskParamMap = JsonUtil.toMap(task.taskParams)

            if (taskAtom == "dispatchVMShutdownTaskAtom") {
                Thread.sleep(3000)
                val buildContainer = buildContainerDao.getContainer(
                    dslContext = dslContext,
                    buildId = task.buildId,
                    stageId = task.stageId,
                    containerId = task.containerId
                )
                if (buildContainer != null) {
                    val dispatchType = taskParamMap["dispatchType"] as Map<String, Any>
                    val dataPlatJobDetail = DataPlatJobDetail(
                        pipelineId = task.pipelineId,
                        buildId = task.buildId,
                        containerType = dispatchType["buildType"].toString(),
                        projectEnglishName = task.projectId,
                        stageId = task.stageId,
                        containerId = task.containerId,
                        jobParams = JSONObject(JsonUtil.toMap(task.taskParams)),
                        status = buildContainer.status.toString(),
                        seq = buildContainer.seq.toString(),
                        startTime = buildContainer.startTime.format(dateTimeFormatter),
                        endTime = buildContainer.endTime.format(dateTimeFormatter),
                        costTime = buildContainer.cost.toLong(),
                        executeCount = buildContainer.executeCount,
                        conditions = JSONObject(JsonUtil.toMap(buildContainer.conditions)),
                        washTime = LocalDateTime.now().format(dateTimeFormatter)
                    )

                    logger.info("pushJobDetail: ${JsonUtil.toJson(dataPlatJobDetail)}")
                    kafkaClient.send(KafkaTopic.LANDUN_JOB_DETAIL_TOPIC, JsonUtil.toJson(dataPlatJobDetail))
                }
            } else {
                val atomCode = taskParamMap["atomCode"].toString()

                val taskParams = if (taskParamMap["@type"] != "marketBuild" || taskParamMap["@type"] != "marketBuildLess") {
                    val inputMap = mutableMapOf("key" to "value")
                    val dataMap = mutableMapOf("input" to inputMap)
                    val taskParamMap1 = mutableMapOf("data" to dataMap)
                    JSONObject(taskParamMap1)
                } else {
                    JSONObject(JsonUtil.toMap(task.taskParams))
                }
                val dataPlatTaskDetail = DataPlatTaskDetail(
                    pipelineId = task.pipelineId,
                    buildId = task.buildId,
                    projectEnglishName = task.projectId,
                    type = "task",
                    itemId = task.taskId,
                    atomCode = atomCode,
                    taskParams = taskParams,
                    status = BuildStatus.values()[task.status].statusName,
                    errorCode = task.errorCode,
                    errorMsg = task.errorMsg,
                    startTime = task.startTime?.format(dateTimeFormatter),
                    endTime = task.endTime?.format(dateTimeFormatter),
                    costTime = if ((endTime - startTime) < 0) 0 else (endTime - startTime),
                    starter = task.starter,
                    washTime = LocalDateTime.now().format(dateTimeFormatter)
                )

                logger.info("pushTaskDetail: ${JsonUtil.toJson(dataPlatTaskDetail)}")
                kafkaClient.send(KafkaTopic.LANDUN_TASK_DETAIL_TOPIC, JsonUtil.toJson(dataPlatTaskDetail))
            }
        } catch (e: Exception) {
            logger.error("Push task detail to kafka error, buildId: ${event.buildId}, taskId: ${event.taskId}", e)
        }
    }

    private fun pushGitTaskInfo(event: PipelineBuildTaskFinishBroadCastEvent, task: TPipelineBuildTaskRecord) {
        try {
            val gitUrl: String
            val taskParamsMap = JsonUtil.toMap(task.taskParams)
            val atomCode = taskParamsMap["atomCode"]
            when (atomCode) {
                "CODE_GIT" -> {
                    val repositoryHashId = taskParamsMap["repositoryHashId"]
                    val gitRepository = client.get(ServiceRepositoryResource::class)
                        .get(event.projectId, repositoryHashId.toString(), RepositoryType.ID)
                    gitUrl = gitRepository.data!!.url
                    sendKafka(task, gitUrl)
                }
                "gitCodeRepoCommon" -> {
                    val dataMap = JsonUtil.toMap(taskParamsMap["data"] ?: error(""))
                    val inputMap = JsonUtil.toMap(dataMap["input"] ?: error(""))
                    gitUrl = inputMap["repositoryUrl"].toString()
                    sendKafka(task, gitUrl)
                }
                "gitCodeRepo", "PullFromGithub", "GitLab" -> {
                    val dataMap = JsonUtil.toMap(taskParamsMap["data"] ?: error(""))
                    val inputMap = JsonUtil.toMap(dataMap["input"] ?: error(""))
                    val repositoryHashId = if (atomCode == "Gitlab") {
                        inputMap["repository"].toString()
                    } else {
                        inputMap["repositoryHashId"].toString()
                    }
                    val gitRepository = client.get(ServiceRepositoryResource::class)
                        .get(event.projectId, repositoryHashId, RepositoryType.ID)
                    gitUrl = gitRepository.data!!.url
                    sendKafka(task, gitUrl)
                }
            }
        } catch (e: Exception) {
            logger.error("Push git task to kafka error, buildId: ${event.buildId}, taskId: ${event.taskId}", e)
        }
    }

    private fun sendKafka(task: TPipelineBuildTaskRecord, gitUrl: String) {
        val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val taskMap = task.intoMap()
        taskMap["GIT_URL"] = gitUrl
        taskMap["WASH_TIME"] = LocalDateTime.now().format(dateTimeFormatter)
        taskMap.remove("TASK_PARAMS")

        kafkaClient.send(KafkaTopic.LANDUN_GIT_TASK_TOPIC, JsonUtil.toJson(taskMap))
    }

    private val projectCache = CacheBuilder.newBuilder()
        .maximumSize(10000)
        .expireAfterAccess(30, TimeUnit.MINUTES)
        .build<String/*Build*/, ProjectOrganize>(
            object : CacheLoader<String, ProjectOrganize>() {
                override fun load(projectId: String): ProjectOrganize {
                    val projectInfo = client.get(ServiceProjectResource::class).get(projectId).data
                    if (projectInfo == null) {
                        logger.warn("[$projectId] Fail to get the project info")
                        throw InvalidParamException(
                            message = "Fail to get the project info, projectId=$projectId",
                            errorCode = ERROR_LAMBDA_PROJECT_NOT_EXIST,
                            params = arrayOf(projectId)
                        )
                    }
                    return ProjectOrganize(
                        projectId = projectId,
                        bgName = projectInfo.bgName ?: "",
                        deptName = projectInfo.deptName ?: "",
                        centerName = projectInfo.centerName ?: ""
                    )
                }
            }
        )

    private val templateCache = CacheBuilder.newBuilder()
        .maximumSize(10000)
        .expireAfterAccess(30, TimeUnit.MINUTES)
        .build<String/*pipelineId*/, String/*templateId*/>(
            object : CacheLoader<String, String>() {
                override fun load(pipelineId: String): String {
                    return pipelineTemplateDao.getTemplate(dslContext, pipelineId)?.templateId ?: ""
                }
            }
        )

    private fun getBuildInfo(buildId: String): BuildInfo? {
        return convert(lambdaPipelineBuildDao.getBuildInfo(dslContext, buildId))
    }

    private fun getModel(pipelineId: String, version: Int): String? {
        return pipelineResDao.getModel(dslContext, pipelineId, version)
    }

    private fun convert(t: TPipelineBuildHistoryRecord?): BuildInfo? {
        return if (t == null) {
            null
        } else {
            BuildInfo(
                projectId = t.projectId,
                pipelineId = t.pipelineId,
                buildId = t.buildId,
                version = t.version,
                buildNum = t.buildNum,
                trigger = t.trigger,
                status = BuildStatus.values()[t.status],
                startUser = t.startUser,
                queueTime = t.queueTime?.timestampmilli() ?: 0L,
                startTime = t.startTime?.timestampmilli() ?: 0L,
                endTime = t.endTime?.timestampmilli() ?: 0L,
                taskCount = t.taskCount,
                firstTaskId = t.firstTaskId,
                parentBuildId = t.parentBuildId,
                parentTaskId = t.parentTaskId,
                channelCode = ChannelCode.valueOf(t.channel),
                errorType = if (t.errorType == null) null else ErrorType.values()[t.errorType],
                errorCode = t.errorCode,
                errorMsg = t.errorMsg
            )
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PipelineBuildService::class.java)
    }
}