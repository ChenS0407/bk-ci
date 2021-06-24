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
import com.tencent.devops.gitci.pojo.v2.GitCIBasicSetting
import com.tencent.devops.gitci.v2.dao.GitCIBasicSettingDao
import com.tencent.devops.gitci.v2.exception.GitCINoEnableException
import com.tencent.devops.project.api.service.service.ServiceTxProjectResource
import com.tencent.devops.project.api.service.service.ServiceTxUserResource
import com.tencent.devops.scm.pojo.GitCIProjectInfo
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
        val setting = gitCIBasicSettingDao.getSetting(dslContext, gitProjectId)
        if (setting == null) {
            logger.info("git repo not exists.")
            return false
        }
        if (!enableUserId.isNullOrBlank()) {
            val projectResult =
                client.get(ServiceTxUserResource::class).get(enableUserId)
            if (projectResult.isNotOk()) {
                logger.error("Update git ci project in devops failed, msg: ${projectResult.message}")
            } else {
                val userInfo = projectResult.data!!
                setting.creatorBgName = userInfo.bgName
                setting.creatorDeptName = userInfo.deptName
                setting.creatorCenterName = userInfo.centerName
            }
        }
        gitCIBasicSettingDao.updateProjectSetting(
            dslContext = dslContext,
            gitProjectId = gitProjectId,
            buildPushedBranches = buildPushedBranches,
            buildPushedPullRequest = buildPushedPullRequest,
            enableMrBlock = enableMrBlock,
            enableCi = enableCi,
            enableUserId = enableUserId,
            creatorBgName = setting.creatorBgName,
            creatorDeptName = setting.creatorDeptName,
            creatorCenterName = setting.creatorCenterName
        )
        return true
    }

    fun getGitCIConf(gitProjectId: Long): GitCIBasicSetting? {
        return gitCIBasicSettingDao.getSetting(dslContext, gitProjectId)
    }

    fun getGitCIBasicSettingAndCheck(gitProjectId: Long): GitCIBasicSetting {
        return gitCIBasicSettingDao.getSetting(dslContext, gitProjectId)
            ?: throw GitCINoEnableException(gitProjectId.toString())
    }

    fun initGitCIConf(
        userId: String,
        projectId: String,
        gitProjectId: Long,
        enabled: Boolean,
        projectInfo: GitCIProjectInfo
    ): Boolean {
        return saveGitCIConf(
            userId,
            GitCIBasicSetting(
                gitProjectId = gitProjectId,
                name = projectInfo.name,
                url = projectInfo.gitSshUrl ?: "",
                homepage = projectInfo.homepage ?: "",
                gitHttpUrl = projectInfo.gitHttpsUrl ?: "",
                gitSshUrl = projectInfo.gitSshUrl ?: "",
                enableCi = enabled,
                enableUserId = userId,
                buildPushedBranches = true,
                buildPushedPullRequest = true,
                enableMrBlock = true,
                projectCode = projectId,
                createTime = null,
                updateTime = null,
                creatorCenterName = null,
                creatorDeptName = null,
                creatorBgName = null
            )
        )
    }

    fun saveGitCIConf(userId: String, setting: GitCIBasicSetting): Boolean {
        logger.info("save git ci conf, repositoryConf: $setting")
        val gitRepoConf = gitCIBasicSettingDao.getSetting(dslContext, setting.gitProjectId)
        if (gitRepoConf?.projectCode == null) {
            val projectResult =
                client.get(ServiceTxProjectResource::class).createGitCIProject(setting.gitProjectId, userId)
            if (projectResult.isNotOk()) {
                throw RuntimeException("Create git ci project in devops failed, msg: ${projectResult.message}")
            }
            val projectInfo = projectResult.data!!
            setting.creatorBgName = projectInfo.bgName
            setting.creatorDeptName = projectInfo.deptName
            setting.creatorCenterName = projectInfo.centerName
            gitCIBasicSettingDao.saveSetting(dslContext, setting, projectInfo.projectCode)
        } else {
            val projectResult =
                client.get(ServiceTxUserResource::class).get(gitRepoConf.enableUserId)
            if (projectResult.isNotOk()) {
                logger.error("Update git ci project in devops failed, msg: ${projectResult.message}")
                return false
            }
            val userInfo = projectResult.data!!
            setting.creatorBgName = userInfo.bgName
            setting.creatorDeptName = userInfo.deptName
            setting.creatorCenterName = userInfo.centerName
            gitCIBasicSettingDao.saveSetting(dslContext, setting, gitRepoConf.projectCode!!)
        }
        return true
    }

    fun fixProjectInfo(): Int {
        val limitCount = 5
        var count = 0
        var startId = 0L
        var endId = startId + limitCount.toLong()
        var currProjects = gitCIBasicSettingDao.getProjectAfterId(dslContext, startId, endId)
        while (currProjects.isNotEmpty()) {
            currProjects.forEach {
                val projectResult =
                    client.get(ServiceTxUserResource::class).get(it.enableUserId)
                if (projectResult.isNotOk()) {
                    logger.error("Update git ci project in devops failed, msg: ${projectResult.message}")
                    return@forEach
                }
                val userInfo = projectResult.data!!
                gitCIBasicSettingDao.updateProjectSetting(
                    dslContext = dslContext,
                    gitProjectId = it.id,
                    buildPushedBranches = null,
                    buildPushedPullRequest = null,
                    enableMrBlock = null,
                    enableCi = null,
                    enableUserId = null,
                    creatorBgName = userInfo.bgName,
                    creatorDeptName = userInfo.deptName,
                    creatorCenterName = userInfo.centerName
                )
                count++
            }
            logger.info("fixProjectInfo project ${currProjects.map { it.id }.toList()}, fixed count: $count")
            Thread.sleep(100)
            startId += limitCount.toLong()
            endId += limitCount.toLong()
            currProjects = gitCIBasicSettingDao.getProjectAfterId(dslContext, startId, limitCount.toLong())
        }
        logger.info("fixProjectInfo finished count: $count")
        return count
    }
}
