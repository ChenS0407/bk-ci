package com.tencent.devops.auth.service.gitci

import com.tencent.devops.auth.service.ManagerService
import com.tencent.devops.auth.service.iam.PermissionService
import com.tencent.devops.common.api.exception.OauthForbiddenException
import com.tencent.devops.common.auth.api.AuthPermission
import com.tencent.devops.common.auth.api.AuthResourceType
import com.tencent.devops.common.auth.utils.GitCIUtils
import com.tencent.devops.common.client.Client
import com.tencent.devops.repository.api.ServiceOauthResource
import com.tencent.devops.scm.api.ServiceGitCiResource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired

class GitCIPermissionServiceImpl @Autowired constructor(
    val client: Client,
    val managerService: ManagerService,
    val projectInfoService: GitCiProjectInfoService
) : PermissionService {

    // GitCI权限场景不会出现次调用, 故做默认实现
    override fun validateUserActionPermission(userId: String, action: String): Boolean {
        return true
    }

    override fun validateUserResourcePermission(
        userId: String,
        action: String,
        projectCode: String,
        resourceType: String?
    ): Boolean {
        // review管理员校验
        try {
            if (reviewManagerCheck(userId, projectCode, action, resourceType ?: "")) {
                logger.info("$projectCode $userId $action $resourceType is review manager")
                return true
            }
        } catch (e: Exception) {
            // 管理员报错不影响主流程, 有种场景gitCI会在项目未创建得时候调权限校验,通过projectId匹配组织会报空指针
            logger.warn("reviewManager fail $e")
        }

        val gitProjectId = GitCIUtils.getGitCiProjectId(projectCode)
        // 操作类action需要校验用户oauth, 查看类的无需oauth校验
        if (!checkListOrViewAction(action)) {
            val checkOauth = client.get(ServiceOauthResource::class).gitGet(userId).data
            if (checkOauth == null) {
                logger.warn("GitCICertPermissionServiceImpl $userId oauth is empty")
                throw OauthForbiddenException("oauth is empty")
            }
        }
        logger.info("GitCICertPermissionServiceImpl user:$userId projectId: $projectCode gitProjectId: $gitProjectId")

        // 判断是否为开源项目
        if (projectInfoService.checkProjectPublic(gitProjectId)) {
            // 若为pipeline 且action为list 校验成功
            if (checkExtAction(action, resourceType)) {
                logger.info("$projectCode is public, views action can check success")
                return true
            }
        }

        val gitUserId = projectInfoService.getGitUserByRtx(userId, gitProjectId)
        if (gitUserId.isNullOrEmpty()) {
            logger.warn("$userId is not gitCI user")
            return false
        }

        val checkResult = client.getScm(ServiceGitCiResource::class)
            .checkUserGitAuth(gitUserId, gitProjectId).data ?: false
        if (!checkResult) {
            logger.warn("$projectCode $userId $action $resourceType check permission fail")
        }
        return checkResult
    }

    override fun validateUserResourcePermissionByRelation(
        userId: String,
        action: String,
        projectCode: String,
        resourceCode: String,
        resourceType: String,
        relationResourceType: String?
    ): Boolean {
        return validateUserResourcePermission(userId, action, projectCode, resourceCode)
    }

    // GitCI权限场景不会出现次调用, 故做默认实现
    override fun getUserResourceByAction(
        userId: String,
        action: String,
        projectCode: String,
        resourceType: String
    ): List<String> {
        return emptyList()
    }

    // GitCI权限场景不会出现次调用, 故做默认实现
    override fun getUserResourcesByActions(
        userId: String,
        actions: List<String>,
        projectCode: String,
        resourceType: String
    ): Map<AuthPermission, List<String>> {
        return emptyMap()
    }

    private fun checkListOrViewAction(action: String): Boolean {
        if (action.contains(AuthPermission.LIST.value) || action.contains(AuthPermission.VIEW.value)) {
            return true
        }
        return false
    }

    private fun checkExtAction(action: String?, resourceCode: String?): Boolean {
        if (action.isNullOrEmpty() || resourceCode.isNullOrEmpty()) {
            return false
        }
        if ((action == AuthPermission.VIEW.value || action == AuthPermission.LIST.value) &&
            resourceCode == AuthResourceType.PIPELINE_DEFAULT.value) {
            return true
        }
        return false
    }

    private fun reviewManagerCheck(
        userId: String,
        projectCode: String,
        action: String,
        resourceTypeStr: String
    ): Boolean {
        if (resourceTypeStr.isNullOrEmpty()) {
            return false
        }
        return try {
            val authPermission = AuthPermission.get(action)
            val resourceType = AuthResourceType.get(resourceTypeStr)
            managerService.isManagerPermission(
                userId = userId,
                projectId = projectCode,
                authPermission = authPermission,
                resourceType = resourceType
            )
        } catch (e: Exception) {
            logger.warn("reviewManagerCheck change enum fail $projectCode $action $resourceTypeStr")
            false
        }
    }

    companion object {
        val logger = LoggerFactory.getLogger(GitCIPermissionServiceImpl::class.java)
    }
}
