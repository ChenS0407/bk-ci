package com.tencent.devops.artifactory.service.permission

import com.tencent.devops.artifactory.service.PipelineService
import com.tencent.devops.auth.api.service.ServicePermissionAuthResource
import com.tencent.devops.common.api.exception.PermissionForbiddenException
import com.tencent.devops.common.auth.api.AuthPermission
import com.tencent.devops.common.auth.utils.GitCIUtils
import com.tencent.devops.common.client.Client
import com.tencent.devops.process.api.service.ServicePipelineResource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired

class GitCIPipelineService @Autowired constructor(
    private val client: Client
) : PipelineService(client) {
    override fun validatePermission(
        userId: String,
        projectId: String,
        pipelineId: String?,
        permission: AuthPermission?,
        message: String?
    ) {
        if (!hasPermission(userId, projectId, pipelineId, permission)) {
            throw PermissionForbiddenException(message)
        }
    }

    override fun hasPermission(
        userId: String,
        projectId: String,
        pipelineId: String?,
        permission: AuthPermission?
    ): Boolean {
        val gitProjectId = GitCIUtils.getGitCiProjectId(projectId)
        logger.info("GitCIPipelineService user:$userId projectId: $projectId gitProject: $gitProjectId")
        return client.get(ServicePermissionAuthResource::class).validateUserResourcePermission(
            userId, "", gitProjectId, null).data ?: false
    }

    override fun filterPipeline(user: String, projectId: String): List<String> {
        if (!hasPermission(user, projectId, null, null)) {
            return emptyList()
        }
        val pipelineInfos = client.get(ServicePipelineResource::class).list(
            userId = user,
            projectId = projectId,
            page = 0,
            pageSize = 1000,
            checkPermission = false
        ).data?.records

        return if (!pipelineInfos.isNullOrEmpty()) {
            pipelineInfos.map { it.pipelineId }
        } else {
            emptyList()
        }
    }

    companion object {
        val logger = LoggerFactory.getLogger(GitCIPipelineService::class.java)
    }
}
