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

package com.tencent.devops.gitci.resources.user

import com.tencent.devops.common.api.exception.CustomException
import com.tencent.devops.common.api.exception.ParamBlankException
import com.tencent.devops.common.api.pojo.Page
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.web.RestResource
import com.tencent.devops.gitci.api.user.UserGitCIHistoryResource
import com.tencent.devops.gitci.pojo.GitCIBuildBranch
import com.tencent.devops.gitci.pojo.GitCIBuildHistory
import com.tencent.devops.gitci.pojo.enums.GitEventEnum
import com.tencent.devops.gitci.service.GitCIHistoryService
import com.tencent.devops.gitci.v2.service.GitCIBasicSettingService
import org.springframework.beans.factory.annotation.Autowired
import javax.ws.rs.core.Response

@RestResource
class UserGitCIHistoryResourceImpl @Autowired constructor(
    private val gitCIHistoryService: GitCIHistoryService,
    private val gitCIBasicSettingService: GitCIBasicSettingService
) : UserGitCIHistoryResource {
    override fun getHistoryBuildList(
        userId: String,
        gitProjectId: Long,
        page: Int?,
        pageSize: Int?,
        branch: String?,
        sourceGitProjectId: Long?,
        triggerUser: String?,
        pipelineId: String?,
        commitMsg: String?,
        event: GitEventEnum?,
        status: BuildStatus?
    ): Result<Page<GitCIBuildHistory>> {
        checkParam(userId)
        if (!gitCIBasicSettingService.initGitCISetting(userId, gitProjectId)) {
            throw CustomException(Response.Status.FORBIDDEN, "项目无法开启工蜂CI，请联系蓝盾助手")
        }
        return Result(
            gitCIHistoryService.getHistoryBuildList(
                userId = userId,
                gitProjectId = gitProjectId,
                page = page, pageSize = pageSize,
                branch = branch,
                sourceGitProjectId = sourceGitProjectId,
                triggerUser = triggerUser,
                pipelineId = pipelineId,
                commitMsg = commitMsg,
                event = event,
                status = status
            )
        )
    }

    override fun getAllBuildBranchList(
        userId: String,
        gitProjectId: Long,
        page: Int?,
        pageSize: Int?,
        keyword: String?
    ): Result<Page<GitCIBuildBranch>> {
        checkParam(userId)
        if (!gitCIBasicSettingService.initGitCISetting(userId, gitProjectId)) {
            throw CustomException(Response.Status.FORBIDDEN, "项目无法开启工蜂CI，请联系蓝盾助手")
        }
        return Result(
            gitCIHistoryService.getAllBuildBranchList(
                userId = userId,
                gitProjectId = gitProjectId,
                page = page ?: 1,
                pageSize = pageSize ?: 20,
                keyword = keyword
            )
        )
    }

    private fun checkParam(userId: String) {
        if (userId.isBlank()) {
            throw ParamBlankException("Invalid userId")
        }
    }
}
