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

package com.tencent.devops.gitci.v2.service

import com.tencent.devops.common.api.pojo.Page
import com.tencent.devops.common.api.util.PageUtil
import com.tencent.devops.gitci.dao.GitPipelineResourceDao
import com.tencent.devops.gitci.dao.GitRequestEventBuildDao
import com.tencent.devops.gitci.dao.GitRequestEventDao
import com.tencent.devops.gitci.pojo.GitProjectPipeline
import com.tencent.devops.gitci.v2.dao.GitCIBasicSettingDao
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class GitCIV2PipelineService @Autowired constructor(
    private val dslContext: DSLContext,
    private val pipelineResourceDao: GitPipelineResourceDao,
    private val gitCIBasicSettingDao: GitCIBasicSettingDao,
    private val gitRequestEventBuildDao: GitRequestEventBuildDao,
    private val gitRequestEventDao: GitRequestEventDao,
    private val gitCIBasicSettingService: GitCIBasicSettingService,
    private val gitCIV2DetailService: GitCIV2DetailService
) {
    companion object {
        private val logger = LoggerFactory.getLogger(GitCIV2PipelineService::class.java)
    }

    fun getPipelineList(
        userId: String,
        gitProjectId: Long,
        keyword: String?,
        page: Int?,
        pageSize: Int?
    ): Page<GitProjectPipeline> {
        logger.info("get pipeline list, gitProjectId: $gitProjectId")
        val pageNotNull = page ?: 1
        val pageSizeNotNull = pageSize ?: 10
        val conf = gitCIBasicSettingDao.getSetting(dslContext, gitProjectId)
        if (conf == null) {
            gitCIBasicSettingService.initGitCISetting(userId, gitProjectId)
            return Page(
                count = 0L,
                page = pageNotNull,
                pageSize = pageSizeNotNull,
                totalPages = 0,
                records = emptyList()
            )
        }
        val limit = PageUtil.convertPageSizeToSQLLimit(pageNotNull, pageSizeNotNull)
        val pipelines = pipelineResourceDao.getPageByGitProjectId(
            dslContext = dslContext,
            gitProjectId = gitProjectId,
            keyword = keyword,
            offset = limit.offset,
            limit = limit.limit
        )
        if (pipelines.isEmpty()) return Page(
            count = 0L,
            page = pageNotNull,
            pageSize = pageSizeNotNull,
            totalPages = 0,
            records = emptyList()
        )
        val count = pipelineResourceDao.getPipelineCount(dslContext, gitProjectId)
        val latestBuilds = gitCIV2DetailService.batchGetBuildDetail(
            userId = userId,
            gitProjectId = gitProjectId,
            buildIds = pipelines.map { it.latestBuildId }
        )
        return Page(
            count = pipelines.size.toLong(),
            page = pageNotNull,
            pageSize = pageSizeNotNull,
            totalPages = count,
            records = pipelines.map {
                GitProjectPipeline(
                    gitProjectId = gitProjectId,
                    pipelineId = it.pipelineId,
                    filePath = it.filePath,
                    displayName = it.displayName,
                    enabled = it.enabled,
                    creator = it.creator,
                    latestBuildInfo = latestBuilds[it.latestBuildId]
                )
            }
        )
    }

    fun getPipelineListWithoutHistory(
        userId: String,
        gitProjectId: Long
    ): List<GitProjectPipeline> {
        logger.info("get pipeline info list, gitProjectId: $gitProjectId")
        val conf = gitCIBasicSettingDao.getSetting(dslContext, gitProjectId)
        if (conf == null) {
            gitCIBasicSettingService.initGitCISetting(userId, gitProjectId)
            return emptyList()
        }
        val pipelines = pipelineResourceDao.getAllByGitProjectId(
            dslContext = dslContext,
            gitProjectId = gitProjectId
        )
        return pipelines.map {
            GitProjectPipeline(
                gitProjectId = gitProjectId,
                pipelineId = it.pipelineId,
                filePath = it.filePath,
                displayName = it.displayName,
                enabled = it.enabled,
                creator = it.creator,
                latestBuildInfo = null
            )
        }
    }

    fun getPipelineListById(
        userId: String,
        gitProjectId: Long,
        pipelineId: String
    ): GitProjectPipeline? {
        logger.info("get pipeline: $pipelineId, gitProjectId: $gitProjectId")
        val conf = gitCIBasicSettingDao.getSetting(dslContext, gitProjectId)
        if (conf == null) {
            gitCIBasicSettingService.initGitCISetting(userId, gitProjectId)
            return null
        }
        val pipeline = pipelineResourceDao.getPipelineById(
            dslContext = dslContext,
            gitProjectId = gitProjectId,
            pipelineId = pipelineId
        ) ?: return null
        return GitProjectPipeline(
            gitProjectId = gitProjectId,
            pipelineId = pipeline.pipelineId,
            filePath = pipeline.filePath,
            displayName = pipeline.displayName,
            enabled = pipeline.enabled,
            creator = pipeline.creator,
            latestBuildInfo = null
        )
    }

    fun getPipelineListWithIds(
        userId: String,
        gitProjectId: Long,
        pipelineIds: List<String>
    ): List<GitProjectPipeline> {
        logger.info("get pipeline list in $pipelineIds, gitProjectId: $gitProjectId")
        val conf = gitCIBasicSettingDao.getSetting(dslContext, gitProjectId)
        if (conf == null) {
            gitCIBasicSettingService.initGitCISetting(userId, gitProjectId)
            return emptyList()
        }
        val pipelines = pipelineResourceDao.getPipelinesInIds(
            dslContext = dslContext,
            gitProjectId = gitProjectId,
            pipelineIds = pipelineIds
        )
        if (pipelines.isEmpty()) return emptyList()
        val latestBuilds = gitCIV2DetailService.batchGetBuildDetail(userId, gitProjectId, pipelines.map { it.latestBuildId })
        return pipelines.map {
            GitProjectPipeline(
                gitProjectId = gitProjectId,
                pipelineId = it.pipelineId,
                filePath = it.filePath,
                displayName = it.displayName,
                enabled = it.enabled,
                creator = it.creator,
                latestBuildInfo = latestBuilds[it.latestBuildId]
            )
        }
    }

    fun enablePipeline(
        userId: String,
        gitProjectId: Long,
        pipelineId: String,
        enabled: Boolean
    ): Boolean {
        logger.info("gitProjectId: $gitProjectId enable pipeline[$pipelineId] to $enabled")
        return pipelineResourceDao.enablePipelineById(
            dslContext = dslContext,
            pipelineId = pipelineId,
            enabled = enabled
        ) == 1
    }
}
