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

import com.tencent.devops.common.api.exception.CustomException
import com.tencent.devops.common.api.util.OkhttpUtils
import com.tencent.devops.common.client.Client
import com.tencent.devops.log.api.ServiceLogResource
import com.tencent.devops.common.log.pojo.QueryLogs
import com.tencent.devops.gitci.dao.GitPipelineResourceDao
import com.tencent.devops.gitci.utils.GitCIPipelineUtils
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

@Service
class GitCILogService @Autowired constructor(
    private val client: Client,
    private val dslContext: DSLContext,
    private val gitPipelineResourceDao: GitPipelineResourceDao
) {
    companion object {
        private val logger = LoggerFactory.getLogger(GitCILogService::class.java)
    }

    @Value("\${gateway.url}")
    private lateinit var gatewayUrl: String

    fun getInitLogs(
        gitProjectId: Long,
        pipelineId: String,
        buildId: String,
        tag: String?,
        jobId: String?,
        executeCount: Int?
    ): QueryLogs {
        logger.info("get init logs, gitProjectId: $gitProjectId, pipelineId: $pipelineId, build: $buildId")
        val pipeline = getProjectPipeline(gitProjectId, pipelineId)

        return client.get(ServiceLogResource::class).getInitLogs(
            projectId = GitCIPipelineUtils.genGitProjectCode(pipeline.gitProjectId),
            pipelineId = pipeline.pipelineId,
            buildId = buildId,
            tag = tag,
            jobId = jobId,
            executeCount = executeCount
        ).data!!
    }

    fun getAfterLogs(
        gitProjectId: Long,
        pipelineId: String,
        buildId: String,
        start: Long,
        tag: String?,
        jobId: String?,
        executeCount: Int?
    ): QueryLogs {
        logger.info("get after logs, gitProjectId: $gitProjectId, pipelineId: $pipelineId, build: $buildId")
        val pipeline = getProjectPipeline(gitProjectId, pipelineId)
        return client.get(ServiceLogResource::class).getAfterLogs(
            projectId = GitCIPipelineUtils.genGitProjectCode(pipeline.gitProjectId),
            pipelineId = pipeline.pipelineId,
            buildId = buildId,
            start = start,
            tag = tag,
            jobId = jobId,
            executeCount = executeCount
        ).data!!
    }

    fun downloadLogs(
        gitProjectId: Long,
        pipelineId: String,
        buildId: String,
        tag: String?,
        jobId: String?,
        executeCount: Int?
    ): Response {
        logger.info("download logs, gitProjectId: $gitProjectId, pipelineId: $pipelineId, build: $buildId")
        val pipeline = getProjectPipeline(gitProjectId, pipelineId)
        val response = OkhttpUtils.doLongGet("http://$gatewayUrl/log/api/service/logs/${GitCIPipelineUtils.genGitProjectCode(pipeline.gitProjectId)}/${pipeline.pipelineId}/$buildId/download?jobId=${jobId ?: ""}")
        return Response
                .ok(response.body()!!.byteStream(), MediaType.APPLICATION_OCTET_STREAM_TYPE)
                .header("content-disposition", "attachment; filename = ${pipeline.pipelineId}-$buildId-log.txt")
                .header("Cache-Control", "no-cache")
                .build()
    }

    private fun getProjectPipeline(gitProjectId: Long, pipelineId: String) =
        gitPipelineResourceDao.getPipelineById(dslContext, gitProjectId, pipelineId)
            ?: throw CustomException(Response.Status.FORBIDDEN, "该流水线不存在或已删除，如有疑问请联系蓝盾助手")
}
