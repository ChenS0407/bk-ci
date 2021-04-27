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

package com.tencent.devops.gitci.service

import com.tencent.devops.common.ci.OBJECT_KIND_MANUAL
import com.tencent.devops.common.ci.yaml.CIBuildYaml
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.pipeline.Model
import com.tencent.devops.common.pipeline.enums.ChannelCode
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.gitci.client.ScmClient
import com.tencent.devops.gitci.dao.GitPipelineResourceDao
import com.tencent.devops.gitci.dao.GitRequestEventBuildDao
import com.tencent.devops.gitci.dao.GitRequestEventNotBuildDao
import com.tencent.devops.gitci.pojo.GitCITriggerLock
import com.tencent.devops.gitci.pojo.GitProjectPipeline
import com.tencent.devops.gitci.pojo.GitRepositoryConf
import com.tencent.devops.gitci.pojo.GitRequestEvent
import com.tencent.devops.gitci.pojo.enums.TriggerReason
import com.tencent.devops.process.api.service.ServiceBuildResource
import com.tencent.devops.process.api.service.ServicePipelineResource
import com.tencent.devops.process.pojo.BuildId
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
abstract class BaseBuildService<T> @Autowired constructor(
    private val client: Client,
    private val scmClient: ScmClient,
    private val dslContext: DSLContext,
    private val redisOperation: RedisOperation,
    private val gitPipelineResourceDao: GitPipelineResourceDao,
    private val gitRequestEventBuildDao: GitRequestEventBuildDao,
    private val gitRequestEventNotBuildDao: GitRequestEventNotBuildDao
) {
    companion object {
        private val logger = LoggerFactory.getLogger(BaseBuildService::class.java)
    }

    private val channelCode = ChannelCode.GIT

    abstract fun gitStartBuild(pipeline: GitProjectPipeline, event: GitRequestEvent, yaml: T, gitBuildId: Long): BuildId?

    fun startBuild(
        pipeline: GitProjectPipeline,
        event: GitRequestEvent,
        gitProjectConf: GitRepositoryConf,
        model: Model,
        gitBuildId: Long
    ): BuildId? {
        val processClient = client.get(ServicePipelineResource::class)
        if (pipeline.pipelineId.isBlank()) {
            // 直接新建
            logger.info("create new gitBuildId:$gitBuildId, pipeline: $pipeline")

            pipeline.pipelineId = processClient.create(event.userId, gitProjectConf.projectCode!!, model, channelCode).data!!.id
            gitPipelineResourceDao.createPipeline(
                dslContext = dslContext,
                gitProjectId = gitProjectConf.gitProjectId,
                pipeline = pipeline
            )
        } else if (needReCreate(processClient, event, gitProjectConf, pipeline)) {
            // 先删除已有数据
            logger.info("recreate gitBuildId:$gitBuildId, pipeline: $pipeline")
            try {
                gitPipelineResourceDao.deleteByPipelineId(dslContext, pipeline.pipelineId)
                processClient.delete(event.userId, gitProjectConf.projectCode!!, pipeline.pipelineId, channelCode)
            } catch (e: Exception) {
                logger.error("failed to delete pipeline resource gitBuildId:$gitBuildId, pipeline: $pipeline", e)
            }
            // 再次新建
            pipeline.pipelineId = processClient.create(event.userId, gitProjectConf.projectCode!!, model, channelCode).data!!.id
            gitPipelineResourceDao.createPipeline(
                dslContext = dslContext,
                gitProjectId = gitProjectConf.gitProjectId,
                pipeline = pipeline
            )
        } else if (pipeline.pipelineId.isNotBlank()) {
            // 已有的流水线需要更新下工蜂CI这里的状态
            logger.info("update gitPipeline gitBuildId:$gitBuildId, pipeline: $pipeline")
            gitPipelineResourceDao.updatePipeline(
                dslContext = dslContext,
                gitProjectId = gitProjectConf.gitProjectId,
                pipelineId = pipeline.pipelineId,
                displayName = pipeline.displayName
            )
        }

        // 修改流水线并启动构建，需要加锁保证事务性
        try {
            logger.info("GitCI Build start, gitProjectId[${gitProjectConf.gitProjectId}], pipelineId[${pipeline.pipelineId}], gitBuildId[$gitBuildId]")
            val buildId = startupPipelineBuild(processClient, gitBuildId, model, event, gitProjectConf, pipeline.pipelineId)
            logger.info("GitCI Build success, gitProjectId[${gitProjectConf.gitProjectId}], pipelineId[${pipeline.pipelineId}], gitBuildId[$gitBuildId], buildId[$buildId]")
            gitPipelineResourceDao.updatePipelineBuildInfo(dslContext, pipeline, buildId)
            gitRequestEventBuildDao.update(dslContext, gitBuildId, pipeline.pipelineId, buildId)
            // 推送启动构建消息,当人工触发时不推送构建消息
            if (event.objectKind != OBJECT_KIND_MANUAL) {
                scmClient.pushCommitCheck(
                    commitId = event.commitId,
                    description = event.description ?: "",
                    mergeRequestId = event.mergeRequestId ?: 0L,
                    buildId = buildId,
                    userId = event.userId,
                    status = "pending",
                    context = "${pipeline.displayName}(${pipeline.filePath})",
                    gitProjectConf = gitProjectConf
                )
            }
            return BuildId(buildId)
        } catch (e: Exception) {
            logger.error("GitCI Build failed, gitProjectId[${gitProjectConf.gitProjectId}], pipelineId[${pipeline.pipelineId}], gitBuildId[$gitBuildId]", e)
            val build = gitRequestEventBuildDao.getByGitBuildId(dslContext, gitBuildId)
            gitRequestEventNotBuildDao.save(
                dslContext = dslContext,
                eventId = event.id!!,
                pipelineId = pipeline.pipelineId,
                filePath = pipeline.filePath,
                originYaml = build?.originYaml,
                normalizedYaml = build?.normalizedYaml,
                reason = TriggerReason.PIPELINE_RUN_ERROR.name,
                reasonDetail = e.message ?: TriggerReason.PIPELINE_RUN_ERROR.detail,
                gitProjectId = event.gitProjectId
            )
            if (build != null) gitRequestEventBuildDao.removeBuild(dslContext, gitBuildId)
        }

        return null
    }

    private fun startupPipelineBuild(processClient: ServicePipelineResource, gitBuildId: Long, model: Model, event: GitRequestEvent, gitProjectConf: GitRepositoryConf, pipelineId: String): String {
        val triggerLock = GitCITriggerLock(redisOperation, gitProjectConf.gitProjectId, pipelineId)
        try {
            triggerLock.lock()
            processClient.edit(event.userId, gitProjectConf.projectCode!!, pipelineId, model, channelCode)
            return client.get(ServiceBuildResource::class).manualStartup(
                userId = event.userId,
                projectId = gitProjectConf.projectCode!!,
                pipelineId = pipelineId,
                values = mapOf(),
                channelCode = channelCode
            ).data!!.id
        } finally {
            triggerLock.unlock()
        }
    }

    private fun needReCreate(processClient: ServicePipelineResource, event: GitRequestEvent, gitProjectConf: GitRepositoryConf, pipeline: GitProjectPipeline): Boolean {
        try {
            val response = processClient.get(event.userId, gitProjectConf.projectCode!!, pipeline.pipelineId, channelCode)
            if (response.isNotOk()) {
                logger.error("get pipeline failed, msg: ${response.message}")
                return true
            }
        } catch (e: Exception) {
            logger.error("get pipeline failed, pipelineId: ${pipeline.pipelineId}, projectCode: ${gitProjectConf.projectCode}, error msg: ${e.message}")
            return true
        }
        return false
    }

/*    private fun createPipelineModel(event: GitRequestEvent, gitProjectConf: GitRepositoryConf, yaml: CIBuildYaml): Model {
        // 先安装插件市场的插件
        installMarketAtom(gitProjectConf, event.userId, GitCiCodeRepoTask.atomCode)
        installMarketAtom(gitProjectConf, event.userId, DockerRunDevCloudTask.atomCode)
        installMarketAtom(gitProjectConf, event.userId, ServiceJobDevCloudTask.atomCode)

        val stageList = mutableListOf<Stage>()

        // 第一个stage，触发类
        val manualTriggerElement = ManualTriggerElement("手动触发", "T-1-1-1")
        val params = createPipelineParams(gitProjectConf, yaml, event)
        val triggerContainer = TriggerContainer("0", "构建触发", listOf(manualTriggerElement), null, null, null, null, params)
        val stage1 = Stage(listOf(triggerContainer), "stage-1")
        stageList.add(stage1)

        // 第二个stage，services初始化
        addServicesStage(yaml, stageList)

        // 其他的stage
        yaml.stages!!.forEachIndexed { stageIndex, stage ->
            val containerList = mutableListOf<Container>()
            stage.stage.forEachIndexed { jobIndex, job ->
                val elementList = mutableListOf<Element>()
                // 根据job类型创建构建容器或者无构建环境容器，默认vmBuild
                if (job.job.type == null || job.job.type == VM_JOB) {
                    // 构建环境容器每个job的第一个插件都是拉代码
                    elementList.add(createGitCodeElement(event, gitProjectConf))
                    makeElementList(job, elementList, gitProjectConf, event.userId)
                    addVmBuildContainer(job, elementList, containerList, jobIndex)
                } else if (job.job.type == NORMAL_JOB) {
                    makeElementList(job, elementList, gitProjectConf, event.userId)
                    addNormalContainer(job, elementList, containerList, jobIndex)
                }
            }

            stageList.add(Stage(containerList, "stage-$stageIndex"))
        }
        return Model(
            name = GitCIPipelineUtils.genBKPipelineName(gitProjectConf.gitProjectId),
            desc = "",
            stages = stageList,
            labels = emptyList(),
            instanceFromTemplate = false,
            pipelineCreator = event.userId
        )
    }*/
}
