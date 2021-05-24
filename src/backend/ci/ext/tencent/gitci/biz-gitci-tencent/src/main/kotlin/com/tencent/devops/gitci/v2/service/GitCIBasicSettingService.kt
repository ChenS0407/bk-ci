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

import com.tencent.devops.common.client.Client
import com.tencent.devops.gitci.pojo.GitRepository
import com.tencent.devops.gitci.pojo.v2.GitCIBasicSetting
import com.tencent.devops.gitci.v2.dao.GitCIBasicSettingDao
import com.tencent.devops.gitci.v2.exception.GitCINoEnableException
import com.tencent.devops.project.api.service.service.ServiceTxProjectResource
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class GitCIBasicSettingService @Autowired constructor(
    private val dslContext: DSLContext,
    private val client: Client,
    private val gitCIBasicSettingDao: GitCIBasicSettingDao
) {
    companion object {
        private val logger = LoggerFactory.getLogger(GitCIBasicSettingService::class.java)
    }

    fun updateProjectSetting(
        gitProjectId: Long,
        buildPushedBranches: Boolean? = null,
        buildPushedPullRequest: Boolean? = null,
        enableMrBlock: Boolean? = null,
        enableCi: Boolean? = null,
        enableUserId: String? = null
    ): Boolean {
        if (gitCIBasicSettingDao.getSetting(dslContext, gitProjectId) == null) {
            logger.info("git repo not exists.")
            return false
        }
        gitCIBasicSettingDao.updateProjectSetting(
            dslContext = dslContext,
            gitProjectId = gitProjectId,
            buildPushedBranches = buildPushedBranches,
            buildPushedPullRequest = buildPushedPullRequest,
            enableMrBlock = enableMrBlock,
            enableCi = enableCi,
            enableUserId = enableUserId
        )
        return true
    }

    fun getGitCIConf(gitProjectId: Long): GitCIBasicSetting? {
        return gitCIBasicSettingDao.getSetting(dslContext, gitProjectId)
    }

    fun getGitCIBasicSettingAndCheck(gitProjectId: Long): GitCIBasicSetting {
        return gitCIBasicSettingDao.getSetting(dslContext, gitProjectId) ?: throw GitCINoEnableException()
    }

    fun initGitCIConf(
        userId: String,
        projectId: String,
        gitProjectId: Long,
        enabled: Boolean,
        repository: GitRepository
    ): Boolean {
        return saveGitCIConf(
            userId,
            GitCIBasicSetting(
                gitProjectId = gitProjectId,
                name = repository.name,
                url = repository.url,
                homepage = repository.homepage,
                gitHttpUrl = repository.gitHttpUrl,
                gitSshUrl = repository.gitSshUrl,
                enableCi = enabled,
                enableUserId = userId,
                buildPushedBranches = true,
                buildPushedPullRequest = true,
                enableMrBlock = true,
                projectCode = projectId,
                createTime = null,
                updateTime = null
            )
        )
    }

    fun saveGitCIConf(userId: String, gitCIBasicSetting: GitCIBasicSetting): Boolean {
        logger.info("save git ci conf, repositoryConf: $gitCIBasicSetting")
        val gitRepoConf = gitCIBasicSettingDao.getSetting(dslContext, gitCIBasicSetting.gitProjectId)
        val projectCode = if (gitRepoConf?.projectCode == null) {
            val projectResult =
                client.get(ServiceTxProjectResource::class).createGitCIProject(gitCIBasicSetting.gitProjectId, userId)
            if (projectResult.isNotOk()) {
                throw RuntimeException("Create git ci project in devops failed, msg: ${projectResult.message}")
            }
            projectResult.data!!.projectCode
        } else {
            gitRepoConf.projectCode
        }
        gitCIBasicSettingDao.saveSetting(dslContext, gitCIBasicSetting, projectCode!!)
        return true
    }
}
