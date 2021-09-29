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

package com.tencent.devops.stream.v2.service

import com.fasterxml.jackson.core.type.TypeReference
import com.tencent.devops.stream.pojo.enums.GitCIProjectType
import com.tencent.devops.stream.v2.dao.GitCIBasicSettingDao
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import com.tencent.devops.common.api.pojo.Pagination
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.api.util.timestamp
import com.tencent.devops.stream.pojo.v2.project.CIInfo
import com.tencent.devops.stream.pojo.v2.project.ProjectCIInfo
import com.tencent.devops.stream.utils.GitCommonUtils
import com.tencent.devops.repository.pojo.enums.GitAccessLevelEnum
import com.tencent.devops.scm.pojo.GitCodeBranchesSort
import com.tencent.devops.scm.pojo.GitCodeProjectsOrder
import com.tencent.devops.common.redis.RedisOperation
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

@Service
class StreamProjectService @Autowired constructor(
    private val dslContext: DSLContext,
    private val redisOperation: RedisOperation,
    private val scmService: ScmService,
    private val oauthService: OauthService,
    private val gitCIBasicSettingDao: GitCIBasicSettingDao
) {

    companion object {
        private val logger = LoggerFactory.getLogger(StreamProjectService::class.java)
        private const val STREAM_USER_PROJECT_HISTORY_SET = "stream:user:project:history:set"
        private const val MAX_STREAM_USER_HISTORY_LENGTH = 10
        private const val MAX_STREAM_USER_HISTORY_DAYS = 90L
    }

    fun getProjectList(
        userId: String,
        type: GitCIProjectType?,
        search: String?,
        page: Int?,
        pageSize: Int?,
        orderBy: GitCodeProjectsOrder?,
        sort: GitCodeBranchesSort?
    ): Pagination<ProjectCIInfo> {
        val realPage = if (page == null || page <= 0) {
            1
        } else {
            page
        }
        val realPageSize = if (pageSize == null || pageSize <= 0) {
            10
        } else {
            pageSize
        }
        val token = oauthService.getAndCheckOauthToken(userId).accessToken
        val gitProjects = scmService.getProjectList(
            accessToken = token,
            userId = userId,
            page = realPage,
            pageSize = realPageSize,
            search = search,
            orderBy = orderBy ?: GitCodeProjectsOrder.UPDATE,
            sort = sort ?: GitCodeBranchesSort.DESC,
            owned = null,
            minAccessLevel = if (type == GitCIProjectType.MY_PROJECT) {
                GitAccessLevelEnum.DEVELOPER
            } else {
                null
            }
        )
        if (gitProjects.isNullOrEmpty()) {
            return Pagination(false, emptyList())
        }
        val projectIdMap = gitCIBasicSettingDao.searchProjectByIds(
            dslContext = dslContext,
            projectIds = gitProjects.map { it.id!! }.toSet()
        ).associateBy { it.id }
        val result = gitProjects.map {
            val project = projectIdMap[it.id]
            // 针对创建流水线异常时，现有代码会创建event记录
            val ciInfo = if (project?.lastCiInfo == null) {
                CIInfo(
                    enableCI = project?.enableCi ?: false,
                    lastBuildId = null,
                    lastBuildStatus = null,
                    lastBuildPipelineId = null,
                    lastBuildMessage = null
                )
            } else {
                JsonUtil.to(project.lastCiInfo, object : TypeReference<CIInfo>() {})
            }
            ProjectCIInfo(
                id = it.id!!,
                projectCode = project?.projectCode,
                public = it.public,
                name = it.name,
                nameWithNamespace = it.pathWithNamespace,
                httpsUrlToRepo = it.httpsUrlToRepo,
                webUrl = it.webUrl,
                avatarUrl = it.avatarUrl,
                description = it.description,
                ciInfo = ciInfo
            )
        }
        return Pagination(
            hasNext = gitProjects.size == realPageSize,
            records = result
        )
    }

    fun addUserProjectHistory(
        userId: String,
        projectId: String
    ) {
        val key = "$STREAM_USER_PROJECT_HISTORY_SET:$userId"
        redisOperation.zadd(key, projectId, LocalDateTime.now().timestamp().toDouble())
        val size = redisOperation.zsize(key) ?: 0
        if (size > MAX_STREAM_USER_HISTORY_LENGTH) {
            // redis zset 是从小到达排列所以最新的一般在最后面, 从0起前往后删，有几个删几个
            redisOperation.zremoveRange(key, 0, size - MAX_STREAM_USER_HISTORY_LENGTH - 1)
        }
    }

    fun getUserProjectHistory(
        userId: String,
        size: Long
    ): List<ProjectCIInfo>? {
        val key = "$STREAM_USER_PROJECT_HISTORY_SET:$userId"
        // 先清理3个月前过期数据
        val expiredTime = LocalDateTime.now().timestamp() - TimeUnit.DAYS.toSeconds(MAX_STREAM_USER_HISTORY_DAYS)
        redisOperation.zremoveRangeByScore(key, 0.0, expiredTime.toDouble())
        val list = redisOperation.zrange(
            key = key,
            start = 0,
            end = if (size - 1 < 0) {
                0
            } else {
                size - 1
            }
        )
        if (list.isNullOrEmpty()) {
            return null
        }
        // zset 默认从小到大排序，所以取反
        val gitProjectIds = list.map { it.removePrefix("git_").toLong() }.reversed()
        val settings = gitCIBasicSettingDao.getBasicSettingList(dslContext, gitProjectIds, null, null)
            .associateBy { it.id }
        val result = mutableListOf<ProjectCIInfo>()
        gitProjectIds.forEach {
            val setting = settings[it] ?: return@forEach
            result.add(
                ProjectCIInfo(
                    id = setting.id,
                    projectCode = setting.projectCode,
                    public = null,
                    name = setting.name,
                    nameWithNamespace = GitCommonUtils.getPathWithNameSpace(setting.gitHttpUrl),
                    httpsUrlToRepo = setting.gitHttpUrl,
                    webUrl = setting.homePage,
                    avatarUrl = setting.gitProjectAvatar,
                    description = setting.gitProjectDesc,
                    ciInfo = if (setting.lastCiInfo == null) {
                        null
                    } else {
                        JsonUtil.to(setting.lastCiInfo, object : TypeReference<CIInfo>() {})
                    }
                )
            )
        }
        return result
    }
}
