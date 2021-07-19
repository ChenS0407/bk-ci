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

package com.tencent.devops.gitci.v2.dao

import com.tencent.devops.common.api.util.timestampmilli
import com.tencent.devops.gitci.pojo.v2.GitCIBasicSetting
import com.tencent.devops.model.gitci.tables.TGitBasicSetting
import com.tencent.devops.model.gitci.tables.records.TGitBasicSettingRecord
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class GitCIBasicSettingDao {

    fun saveSetting(
        dslContext: DSLContext,
        conf: GitCIBasicSetting,
        projectCode: String
    ) {
        with(TGitBasicSetting.T_GIT_BASIC_SETTING) {
            dslContext.transaction { configuration ->
                val context = DSL.using(configuration)
                val record = context.selectFrom(this)
                    .where(ID.eq(conf.gitProjectId))
                    .fetchAny()
                val now = LocalDateTime.now()
                if (record == null) {
                    context.insertInto(
                        this,
                        ID,
                        NAME,
                        URL,
                        HOME_PAGE,
                        GIT_HTTP_URL,
                        GIT_SSH_URL,
                        ENABLE_CI,
                        BUILD_PUSHED_BRANCHES,
                        BUILD_PUSHED_PULL_REQUEST,
                        CREATE_TIME,
                        UPDATE_TIME,
                        PROJECT_CODE,
                        ENABLE_MR_BLOCK,
                        ENABLE_USER_ID,
                        CREATOR_BG_NAME,
                        CREATOR_DEPT_NAME,
                        CREATOR_CENTER_NAME
                    )
                        .values(
                            conf.gitProjectId,
                            conf.name,
                            conf.url,
                            conf.homepage,
                            conf.gitHttpUrl,
                            conf.gitSshUrl,
                            conf.enableCi,
                            conf.buildPushedBranches,
                            conf.buildPushedPullRequest,
                            LocalDateTime.now(),
                            LocalDateTime.now(),
                            projectCode,
                            conf.enableMrBlock,
                            conf.enableUserId,
                            conf.creatorBgName,
                            conf.creatorDeptName,
                            conf.creatorCenterName
                        ).execute()
                } else {
                    context.update(this)
                        .set(ENABLE_CI, conf.enableCi)
                        .set(BUILD_PUSHED_BRANCHES, conf.buildPushedBranches)
                        .set(BUILD_PUSHED_PULL_REQUEST, conf.buildPushedPullRequest)
                        .set(UPDATE_TIME, now)
                        .set(PROJECT_CODE, projectCode)
                        .set(ENABLE_MR_BLOCK, conf.enableMrBlock)
                        .set(ENABLE_USER_ID, conf.enableUserId)
                        .set(CREATOR_BG_NAME, conf.creatorBgName)
                        .set(CREATOR_DEPT_NAME, conf.creatorDeptName)
                        .set(CREATOR_CENTER_NAME, conf.creatorCenterName)
                        .where(ID.eq(conf.gitProjectId))
                        .execute()
                }
            }
        }
    }

    fun updateInfoSetting(
        dslContext: DSLContext,
        gitProjectId: Long,
        gitProjectName: String,
        url: String,
        sshUrl: String,
        httpUrl: String,
        homePage: String
    ) {
        with(TGitBasicSetting.T_GIT_BASIC_SETTING) {
            dslContext.transaction { configuration ->
                DSL.using(configuration).update(this)
                    .set(NAME, gitProjectName)
                    .set(URL, url)
                    .set(HOME_PAGE, homePage)
                    .set(GIT_HTTP_URL, httpUrl)
                    .set(GIT_SSH_URL, sshUrl)
                    .set(UPDATE_TIME, LocalDateTime.now())
                    .where(ID.eq(gitProjectId))
                    .execute()
            }
        }
    }

    fun updateProjectSetting(
        dslContext: DSLContext,
        gitProjectId: Long,
        buildPushedBranches: Boolean?,
        buildPushedPullRequest: Boolean?,
        enableMrBlock: Boolean?,
        enableCi: Boolean?,
        enableUserId: String?,
        creatorBgName: String?,
        creatorDeptName: String?,
        creatorCenterName: String?
    ) {
        with(TGitBasicSetting.T_GIT_BASIC_SETTING) {
            val dsl = dslContext.update(this)
            if (buildPushedBranches != null) {
                dsl.set(BUILD_PUSHED_BRANCHES, buildPushedBranches)
            }
            if (buildPushedPullRequest != null) {
                dsl.set(BUILD_PUSHED_PULL_REQUEST, buildPushedPullRequest)
            }
            if (enableMrBlock != null) {
                dsl.set(ENABLE_MR_BLOCK, enableMrBlock)
            }
            if (enableCi != null) {
                dsl.set(ENABLE_CI, enableCi)
            }
            if (enableUserId != null) {
                dsl.set(ENABLE_USER_ID, enableUserId)
            }
            if (enableUserId != null) {
                dsl.set(CREATOR_BG_NAME, creatorBgName)
            }
            if (enableUserId != null) {
                dsl.set(CREATOR_DEPT_NAME, creatorDeptName)
            }
            if (enableUserId != null) {
                dsl.set(CREATOR_CENTER_NAME, creatorCenterName)
            }
            dsl.set(UPDATE_TIME, LocalDateTime.now())
                .where(ID.eq(gitProjectId))
                .execute()
        }
    }

    fun getSetting(dslContext: DSLContext, gitProjectId: Long): GitCIBasicSetting? {
        with(TGitBasicSetting.T_GIT_BASIC_SETTING) {
            val conf = dslContext.selectFrom(this)
                .where(ID.eq(gitProjectId))
                .fetchAny()
            if (conf == null) {
                return null
            } else {
                return GitCIBasicSetting(
                    gitProjectId = conf.id,
                    name = conf.name,
                    url = conf.url,
                    homepage = conf.homePage,
                    gitHttpUrl = conf.gitHttpUrl,
                    gitSshUrl = conf.gitSshUrl,
                    enableCi = conf.enableCi,
                    buildPushedBranches = conf.buildPushedBranches,
                    buildPushedPullRequest = conf.buildPushedPullRequest,
                    createTime = conf.createTime.timestampmilli(),
                    updateTime = conf.updateTime.timestampmilli(),
                    projectCode = conf.projectCode,
                    enableMrBlock = conf.enableMrBlock,
                    enableUserId = conf.enableUserId,
                    creatorBgName = conf.creatorBgName,
                    creatorDeptName = conf.creatorDeptName,
                    creatorCenterName = conf.creatorCenterName
                )
            }
        }
    }

    fun getProjectAfterId(dslContext: DSLContext, startId: Long, limit: Int): List<TGitBasicSettingRecord> {
        with(TGitBasicSetting.T_GIT_BASIC_SETTING) {
            return dslContext.selectFrom(this)
                .where(ID.gt(startId))
                .limit(limit)
                .fetch()
        }
    }

    fun fixProjectInfo(
        dslContext: DSLContext,
        gitProjectId: Long,
        creatorBgName: String,
        creatorDeptName: String,
        creatorCenterName: String
    ): Int {
        with(TGitBasicSetting.T_GIT_BASIC_SETTING) {
            return dslContext.update(this)
                .set(CREATOR_BG_NAME, creatorBgName)
                .set(CREATOR_DEPT_NAME, creatorDeptName)
                .set(CREATOR_CENTER_NAME, creatorCenterName)
                .where(ID.eq(gitProjectId))
                .execute()
        }
    }

    fun searchProjectByIds(
        dslContext: DSLContext,
        projectIds: Set<Long>?
    ): List<TGitBasicSettingRecord> {
        if (projectIds.isNullOrEmpty()) {
            return emptyList()
        }
        with(TGitBasicSetting.T_GIT_BASIC_SETTING) {
            return dslContext.selectFrom(this)
                .where(ID.`in`(projectIds))
                .fetch()
        }
    }
}
