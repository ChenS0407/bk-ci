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

package com.tencent.devops.project.service.impl

import com.fasterxml.jackson.module.kotlin.readValue
import com.tencent.bkrepo.common.api.util.JsonUtils.objectMapper
import com.tencent.devops.auth.service.ManagerService
import com.tencent.devops.common.api.exception.OperationException
import com.tencent.devops.common.api.util.OkhttpUtils
import com.tencent.devops.common.archive.client.BkRepoClient
import com.tencent.devops.common.auth.api.AuthPermission
import com.tencent.devops.common.auth.api.AuthPermissionApi
import com.tencent.devops.common.auth.api.AuthProjectApi
import com.tencent.devops.common.auth.api.AuthResourceType
import com.tencent.devops.common.auth.api.BkAuthProperties
import com.tencent.devops.common.auth.api.pojo.BkAuthGroup
import com.tencent.devops.common.auth.code.BSPipelineAuthServiceCode
import com.tencent.devops.common.auth.code.ProjectAuthServiceCode
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.common.service.gray.Gray
import com.tencent.devops.common.service.gray.RepoGray
import com.tencent.devops.common.service.utils.MessageCodeUtil
import com.tencent.devops.project.constant.ProjectMessageCode
import com.tencent.devops.project.dao.ProjectDao
import com.tencent.devops.project.dispatch.ProjectDispatcher
import com.tencent.devops.project.jmx.api.ProjectJmxApi
import com.tencent.devops.project.pojo.AuthProjectForList
import com.tencent.devops.project.pojo.ProjectCreateExtInfo
import com.tencent.devops.project.pojo.ProjectCreateInfo
import com.tencent.devops.project.pojo.ProjectUpdateInfo
import com.tencent.devops.project.pojo.ProjectVO
import com.tencent.devops.project.pojo.Result
import com.tencent.devops.project.pojo.tof.Response
import com.tencent.devops.project.pojo.user.UserDeptDetail
import com.tencent.devops.project.service.ProjectPaasCCService
import com.tencent.devops.project.service.ProjectPermissionService
import com.tencent.devops.project.service.s3.S3Service
import com.tencent.devops.project.service.tof.TOFService
import com.tencent.devops.project.util.ImageUtil
import com.tencent.devops.project.util.ProjectUtils
import okhttp3.Request
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.io.File
import java.util.ArrayList

@Service
class TxProjectServiceImpl @Autowired constructor(
    projectPermissionService: ProjectPermissionService,
    private val dslContext: DSLContext,
    private val projectDao: ProjectDao,
    private val s3Service: S3Service,
    private val tofService: TOFService,
    private val bkRepoClient: BkRepoClient,
    private val repoGray: RepoGray,
    private val projectPaasCCService: ProjectPaasCCService,
    private val bkAuthProperties: BkAuthProperties,
    private val bsAuthProjectApi: AuthProjectApi,
    private val bsPipelineAuthServiceCode: BSPipelineAuthServiceCode,
    projectJmxApi: ProjectJmxApi,
    redisOperation: RedisOperation,
    gray: Gray,
    client: Client,
    projectDispatcher: ProjectDispatcher,
    private val authPermissionApi: AuthPermissionApi,
    private val projectAuthServiceCode: ProjectAuthServiceCode,
    private val managerService: ManagerService
) : AbsProjectServiceImpl(projectPermissionService, dslContext, projectDao, projectJmxApi, redisOperation, gray, client, projectDispatcher, authPermissionApi, projectAuthServiceCode) {

    private var authUrl: String = "${bkAuthProperties.url}/projects"

    override fun getByEnglishName(userId: String, englishName: String, accessToken: String?): ProjectVO? {
        val projectVO = getInfoByEnglishName(englishName)
        if (projectVO == null) {
            logger.warn("The projectCode $englishName is not exist")
            return null
        }
        // 判断用户是否为管理员，若为管理员则不调用iam
        val isManager = managerService.isManagerPermission(
            userId = userId,
            projectId = englishName,
            resourceType = AuthResourceType.PROJECT,
            authPermission = AuthPermission.VIEW
        )

        if (isManager) {
            logger.info("getByEnglishName $userId is $englishName manager")
            return projectVO
        }

        val projectAuthIds = getProjectFromAuth("", accessToken)
        if (projectAuthIds == null || projectAuthIds.isEmpty()) {
            return null
        }
        if (!projectAuthIds.contains(projectVO!!.projectId)) {
            logger.warn("The user don't have the permission to get the project $englishName")
            return null
        }
        return projectVO
    }

    override fun list(userId: String, accessToken: String?): List<ProjectVO> {
        val startEpoch = System.currentTimeMillis()
        try {

            val projects = getProjectFromAuth(userId, accessToken).toSet()
            if (projects == null || projects.isEmpty()) {
                return emptyList()
            }
            logger.info("项目列表：$projects")
            val list = ArrayList<ProjectVO>()
            projectDao.list(dslContext, projects).map {
                list.add(ProjectUtils.packagingBean(it, grayProjectSet()))
            }
            return list
        } finally {
            logger.info("It took ${System.currentTimeMillis() - startEpoch}ms to list projects")
        }
    }

    override fun getDeptInfo(userId: String): UserDeptDetail {
        return tofService.getUserDeptDetail(userId, "") // 获取用户机构信息
    }

    override fun createExtProjectInfo(userId: String, projectId: String, accessToken: String?, projectCreateInfo: ProjectCreateInfo, projectCreateExtInfo: ProjectCreateExtInfo) {
        // 添加repo项目
        val createSuccess = bkRepoClient.createBkRepoResource(userId, projectCreateInfo.englishName)
        logger.info("create bkrepo project ${projectCreateInfo.englishName} success: $createSuccess")

        if (!accessToken.isNullOrEmpty() && projectCreateExtInfo.needAuth!!) {
            // 添加paas项目
            projectPaasCCService.createPaasCCProject(
                userId = userId,
                projectId = projectId,
                accessToken = accessToken!!,
                projectCreateInfo = projectCreateInfo
            )
        }
    }

    override fun saveLogoAddress(userId: String, projectCode: String, logoFile: File): String {
        return s3Service.saveLogo(logoFile, projectCode)
    }

    override fun deleteAuth(projectId: String, accessToken: String?) {
        logger.warn("Deleting the project $projectId from auth")
        try {
            val url = "$authUrl/$projectId?access_token=$accessToken"
            val request = Request.Builder().url(url).delete().build()
            val responseContent = request(request, "Fail to delete the project $projectId")
            logger.info("Get the delete project $projectId response $responseContent")
            val response: Response<Any?> = objectMapper.readValue(responseContent)
            if (response.code.toInt() != 0) {
                logger.warn("Fail to delete the project $projectId with response $responseContent")
            }
            logger.info("Finish deleting the project $projectId from auth")
        } catch (t: Throwable) {
            logger.warn("Fail to delete the project $projectId from auth", t)
        }
    }

    override fun getProjectFromAuth(userId: String?, accessToken: String?): List<String> {
        val url = "$authUrl?access_token=$accessToken"
        logger.info("Start to get auth projects - ($url)")
        val request = Request.Builder().url(url).get().build()
        val responseContent = request(request, MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.PEM_QUERY_ERROR))
        val result = objectMapper.readValue<Result<ArrayList<AuthProjectForList>>>(responseContent)
        if (result.isNotOk()) {
            logger.warn("Fail to get the project info with response $responseContent")
            throw OperationException(MessageCodeUtil.getCodeLanMessage(ProjectMessageCode.PEM_QUERY_ERROR))
        }
        if (result.data == null) {
            return emptyList()
        }

        return result.data!!.map {
            it.project_id
        }
    }

    override fun updateInfoReplace(projectUpdateInfo: ProjectUpdateInfo) {
        val appName = if (projectUpdateInfo.ccAppId != null && projectUpdateInfo.ccAppId!! > 0) {
            tofService.getCCAppName(projectUpdateInfo.ccAppId!!)
        } else {
            null
        }
        projectUpdateInfo.ccAppName = appName
    }

    private fun request(request: Request, errorMessage: String): String {
        OkhttpUtils.doHttp(request).use { response ->
            val responseContent = response.body()!!.string()
            if (!response.isSuccessful) {
                logger.warn("Fail to request($request) with code ${response.code()} , message ${response.message()} and response $responseContent")
                throw OperationException(errorMessage)
            }
            return responseContent
        }
    }

    override fun drawFile(projectCode: String): File {
        // 随机生成首字母图片
        val firstChar = projectCode.substring(0, 1).toUpperCase()
        return ImageUtil.drawImage(firstChar)
    }

    override fun validatePermission(projectCode: String, userId: String, permission: AuthPermission): Boolean {
        val group = if (permission == AuthPermission.MANAGE) {
            BkAuthGroup.MANAGER
        } else {
            null
        }
        return bsAuthProjectApi.isProjectUser(userId, bsPipelineAuthServiceCode, projectCode, group)
    }

    override fun modifyProjectAuthResource(projectCode: String, projectName: String) {
        return
    }

    fun getInfoByEnglishName(englishName: String): ProjectVO? {
        val record = projectDao.getByEnglishName(dslContext, englishName) ?: return null
        return ProjectUtils.packagingBean(record, grayProjectSet())
    }

    override fun hasCreatePermission(userId: String): Boolean {
        return true
    }

    override fun organizationMarkUp(projectCreateInfo: ProjectCreateInfo, userDeptDetail: UserDeptDetail): ProjectCreateInfo {
        val bgId = if (projectCreateInfo.bgId == 0L) userDeptDetail.bgId.toLong() else projectCreateInfo.bgId
        val deptId = if (projectCreateInfo.deptId == 0L) userDeptDetail.deptId.toLong() else projectCreateInfo.deptId
        val centerId = if (projectCreateInfo.centerId == 0L) userDeptDetail.centerId.toLong() else projectCreateInfo.centerId
        val bgName = if (projectCreateInfo.bgName.isNullOrEmpty()) userDeptDetail.bgName else projectCreateInfo.bgName
        val deptName = if (projectCreateInfo.deptName.isNullOrEmpty()) userDeptDetail.deptName else projectCreateInfo.deptName
        val centerName = if (projectCreateInfo.centerName.isNullOrEmpty()) userDeptDetail.centerName else projectCreateInfo.centerName

        return ProjectCreateInfo(
            projectName = projectCreateInfo.projectName,
            projectType = projectCreateInfo.projectType,
            secrecy = projectCreateInfo.secrecy,
            description = projectCreateInfo.description,
            kind = projectCreateInfo.kind,
            bgId = bgId,
            bgName = bgName,
            centerId = centerId,
            centerName = centerName,
            deptId = deptId,
            deptName = deptName,
            englishName = projectCreateInfo.englishName
        )
    }

    companion object {
        private const val Width = 128
        private const val Height = 128
        private val logger = LoggerFactory.getLogger(TxProjectServiceImpl::class.java)!!
    }
}
