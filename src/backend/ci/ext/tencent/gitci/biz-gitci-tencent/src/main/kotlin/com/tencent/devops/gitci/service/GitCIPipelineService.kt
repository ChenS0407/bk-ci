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

package com.tencent.devops.gitci.service

import com.tencent.devops.common.api.pojo.Page
import com.tencent.devops.common.api.util.PageUtil
import com.tencent.devops.common.api.util.timestampmilli
import com.tencent.devops.common.pipeline.enums.ChannelCode
import com.tencent.devops.gitci.dao.GitCISettingDao
import com.tencent.devops.gitci.dao.GitPipelineResourceDao
import com.tencent.devops.gitci.pojo.GitProjectPipeline
import com.tencent.devops.gitci.utils.GitCIPipelineUtils
import com.tencent.devops.process.pojo.BuildHistory
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class GitCIPipelineService @Autowired constructor(
    private val dslContext: DSLContext,
    private val gitCISettingDao: GitCISettingDao,
    private val pipelineResourceDao: GitPipelineResourceDao,
    private val repositoryConfService: RepositoryConfService,
    private val gitCIDetailService: GitCIDetailService
) {
    companion object {
        private val logger = LoggerFactory.getLogger(GitCIPipelineService::class.java)
    }

    fun getPipelineList(
        userId: String,
        gitProjectId: Long,
        page: Int?,
        pageSize: Int?
    ): Page<GitProjectPipeline> {
        logger.info("get history build list, gitProjectId: $gitProjectId")
        val pageNotNull = page ?: 1
        val pageSizeNotNull = pageSize ?: 10
        val conf = gitCISettingDao.getSetting(dslContext, gitProjectId)
        if (conf == null) {
            repositoryConfService.initGitCISetting(userId, gitProjectId)
            return Page(
                count = 0L,
                page = pageNotNull,
                pageSize = pageSizeNotNull,
                totalPages = 0,
                records = emptyList()
            )
        }
        val limit = PageUtil.convertPageSizeToSQLLimit(pageNotNull, pageSizeNotNull)
        val pipelines = pipelineResourceDao.getListByGitProjectId(
            dslContext = dslContext,
            gitProjectId = gitProjectId,
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
        val latestBuilds = gitCIDetailService.getBuildSummary(userId, gitProjectId, pipelines.map { it.latestBuildId })
        return Page(
            count = pipelines.size.toLong(),
            page = pageNotNull,
            pageSize = pageSizeNotNull,
            totalPages = count,
            records = pipelines.map {
                GitProjectPipeline(
                    gitProjectId = gitProjectId,
                    projectCode = GitCIPipelineUtils.genGitProjectCode(gitProjectId),
                    pipelineId = it.pipelineId,
                    branch = it.branch,
                    filePath = it.filePath,
                    displayName = it.displayName,
                    enabled = it.enabled,
                    creator = it.creator,
                    latestBuildDetail = latestBuilds[it.latestBuildId],
                    createTime = it.createTime.timestampmilli(),
                    updateTime = it.updateTime.timestampmilli()
                )
            }
        )
    }
}
