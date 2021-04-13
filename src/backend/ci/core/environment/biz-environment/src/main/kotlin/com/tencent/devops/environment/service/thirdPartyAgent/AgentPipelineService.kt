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

package com.tencent.devops.environment.service.thirdPartyAgent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.tencent.devops.common.api.util.HashUtil
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.event.pojo.pipeline.PipelineModelAnalysisEvent
import com.tencent.devops.common.pipeline.Model
import com.tencent.devops.common.pipeline.container.VMBuildContainer
import com.tencent.devops.common.pipeline.enums.ChannelCode
import com.tencent.devops.common.pipeline.type.agent.ThirdPartyAgentIDDispatchType
import com.tencent.devops.environment.dao.NodeDao
import com.tencent.devops.environment.dao.thirdPartyAgent.AgentPipelineRefDao
import com.tencent.devops.environment.dao.thirdPartyAgent.ThirdPartyAgentDao
import com.tencent.devops.environment.pojo.thirdPartyAgent.AgentPipelineRef
import com.tencent.devops.model.environment.tables.records.TEnvironmentThirdpartyAgentRecord
import com.tencent.devops.process.api.service.ServicePipelineResource
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.format.DateTimeFormatter

@Service
class AgentPipelineService @Autowired constructor(
    private val client: Client,
    private val dslContext: DSLContext,
    private val agentPipelineRefDao: AgentPipelineRefDao,
    private val thirdPartyAgentDao: ThirdPartyAgentDao,
    private val nodeDao: NodeDao,
    private val objectMapper: ObjectMapper
) {
    fun analysisPipelineRefAndSave(event: PipelineModelAnalysisEvent) {
        with(event) {
            logger.info("analysisAndSave, [$source|$projectId|$pipelineId]")
            when (source) {
                "create_pipeline", "update_pipeline", "restore_pipeline" -> {
                    val pipelineModel = objectMapper.readValue<Model>(model)
                    analysisPipelineRefAndSave(projectId, pipelineId, pipelineModel)
                }
                "delete_pipeline" -> {
                    cleanPipelineRef(projectId, pipelineId)
                }
                else -> {
                    logger.warn("source($source) not supported")
                }
            }
        }
    }

    private fun analysisPipelineRefAndSave(projectId: String, pipelineId: String, pipelineModel: Model) {
        val agentBuffer = mutableMapOf<Long, TEnvironmentThirdpartyAgentRecord>()
        val agentPipelineRefs = mutableListOf<AgentPipelineRef>()
        pipelineModel.stages.forEach { stage ->
            stage.containers.forEach { container ->
                if (container is VMBuildContainer && container.dispatchType is ThirdPartyAgentIDDispatchType) {
                    val agentHashId = (container.dispatchType!! as ThirdPartyAgentIDDispatchType).displayName
                    val agentId = HashUtil.decodeIdToLong(agentHashId)
                    val agent = agentBuffer[agentId] ?: thirdPartyAgentDao.getAgent(dslContext, agentId)!!
                    agentPipelineRefs.add(
                        AgentPipelineRef(
                            agentId = agent.id,
                            nodeId = agent.nodeId,
                            projectId = projectId,
                            pipelineId = pipelineId,
                            pipelineName = pipelineModel.name,
                            vmSeqId = container.id,
                            jobId = container.containerId,
                            jobName = container.name
                        )
                    )
                }
            }
        }

        savePipelineRef(projectId, pipelineId, agentPipelineRefs)
    }

    private fun savePipelineRef(projectId: String, pipelineId: String, agentPipelineRefs: List<AgentPipelineRef>) {
        val modifiedAgentIds = mutableSetOf<Long>()
        dslContext.transaction { configuration ->
            val transactionContext = DSL.using(configuration)
            val existPipelineRefs = agentPipelineRefDao.list(transactionContext, projectId, pipelineId)
            val existRefMap = existPipelineRefs.associateBy { "${it.agentId!!}_${it.vmSeqId}_${it.jobId}" }
            val refMap = agentPipelineRefs.associateBy { "${it.agentId!!}_${it.vmSeqId}_${it.jobId}" }
            val toDeleteRefMap = existRefMap.filterKeys { !refMap.containsKey(it) }
            val toAddRefMap = refMap.filterKeys { !existRefMap.containsKey(it) }

            val toDeleteRef = toDeleteRefMap.values
            val toAddRef = toAddRefMap.values

            agentPipelineRefDao.batchDelete(transactionContext, toDeleteRef.map { it.id })
            agentPipelineRefDao.batchAdd(transactionContext, toAddRef)
            modifiedAgentIds.addAll(toDeleteRef.map { it.agentId })
            modifiedAgentIds.addAll(toAddRef.map { it.agentId!! })
        }

        logger.info("savePipelineRef, modifiedAgentIds: $modifiedAgentIds")
        modifiedAgentIds.forEach {
            updateRefCount(it)
        }
    }

    fun cleanPipelineRef(projectId: String, pipelineId: String) {
        dslContext.transaction { configuration ->
            val transactionContext = DSL.using(configuration)
            val existPipelineRefs = agentPipelineRefDao.list(transactionContext, projectId, pipelineId)
            agentPipelineRefDao.batchDelete(transactionContext, existPipelineRefs.map { it.id })
        }
    }

    private fun updateRefCount(agentId: Long) {
        dslContext.transaction { configuration ->
            val transactionContext = DSL.using(configuration)
            val agent = thirdPartyAgentDao.getAgent(dslContext, agentId)
            if (agent != null) {
                val agentRefCount = agentPipelineRefDao.countPipelineRef(dslContext, agentId)
                nodeDao.updatePipelineRefCount(dslContext, agent.nodeId, agentRefCount)
            } else {
                logger.warn("agent[$agentId] not found")
            }
        }
    }

    fun listPipelineRef(userId: String, projectId: String, nodeHashId: String): List<AgentPipelineRef> {
        val nodeLongId = HashUtil.decodeIdToLong(nodeHashId)
        return agentPipelineRefDao.listByNodeId(dslContext, projectId, nodeLongId).map {
            AgentPipelineRef(
                nodeHashId = HashUtil.encodeLongId(it.nodeId),
                agentHashId = HashUtil.encodeLongId(it.agentId),
                projectId = it.projectId,
                pipelineId = it.pipelineId,
                pipelineName = it.pieplineName,
                vmSeqId = it.vmSeqId,
                jobId = it.jobId,
                jobName = it.jobName,
                lastBuildTime = if (null == it.lastBuildTime) {
                    ""
                } else {
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(it.lastBuildTime)
                }
            )
        }
    }

    fun updatePipelineRef(userId: String, projectId: String, pipelineId: String) {
        val model = client.get(ServicePipelineResource::class).get(userId, projectId, pipelineId, ChannelCode.BS).data
        analysisPipelineRefAndSave(projectId, pipelineId, model!!)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AgentPipelineService::class.java)
    }
}
