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

package com.tencent.devops.environment.permission.impl

import com.tencent.devops.auth.api.service.ServicePermissionAuthResource
import com.tencent.devops.auth.service.ManagerService
import com.tencent.devops.common.api.util.HashUtil
import com.tencent.devops.common.auth.api.AuthPermission
import com.tencent.devops.common.auth.api.AuthResourceType
import com.tencent.devops.common.auth.utils.TActionUtils
import com.tencent.devops.common.client.Client
import com.tencent.devops.environment.dao.EnvDao
import com.tencent.devops.environment.dao.NodeDao
import com.tencent.devops.environment.permission.EnvironmentPermissionService
import org.jooq.DSLContext
import org.slf4j.LoggerFactory

class TxV3EnvironmentPermissionService constructor(
    private val dslContext: DSLContext,
    private val client: Client,
    private val envDao: EnvDao,
    private val nodeDao: NodeDao,
    private val managerService: ManagerService
) : EnvironmentPermissionService {

    override fun checkEnvPermission(
        userId: String,
        projectId: String,
        envId: Long,
        permission: AuthPermission
    ): Boolean {
        if (managerService.isManagerPermission(
                userId = userId,
                projectId = projectId,
                authPermission = permission,
                resourceType = AuthResourceType.ENVIRONMENT_ENVIRONMENT
        )) {
            return true
        }

        return client.get(ServicePermissionAuthResource::class).validateUserResourcePermissionByRelation(
            userId = userId,
            projectCode = projectId,
            resourceCode = envId.toString(),
            resourceType = AuthResourceType.ENVIRONMENT_ENVIRONMENT.value,
            relationResourceType = null,
            action = buildEnvAction(permission)
        ).data ?: false
    }

    override fun checkEnvPermission(userId: String, projectId: String, permission: AuthPermission): Boolean {
        if (managerService.isManagerPermission(
                userId = userId,
                projectId = projectId,
                authPermission = permission,
                resourceType = AuthResourceType.ENVIRONMENT_ENVIRONMENT
            )) {
            return true
        }
        return client.get(ServicePermissionAuthResource::class).validateUserResourcePermissionByRelation(
            userId = userId,
            projectCode = projectId,
            resourceCode = projectId,
            resourceType = AuthResourceType.ENVIRONMENT_ENVIRONMENT.value,
            relationResourceType = null,
            action = buildEnvAction(permission)
        ).data ?: false
    }

    override fun checkNodePermission(
        userId: String,
        projectId: String,
        nodeId: Long,
        permission: AuthPermission
    ): Boolean {
        if (managerService.isManagerPermission(
                userId = userId,
                projectId = projectId,
                authPermission = permission,
                resourceType = AuthResourceType.ENVIRONMENT_ENV_NODE
            )) {
            return true
        }
        return client.get(ServicePermissionAuthResource::class).validateUserResourcePermissionByRelation(
            userId = userId,
            projectCode = projectId,
            resourceCode = nodeId.toString(),
            resourceType = AuthResourceType.ENVIRONMENT_ENV_NODE.value,
            relationResourceType = null,
            action = buildNodeAction(permission)
        ).data ?: false
    }

    override fun checkNodePermission(userId: String, projectId: String, permission: AuthPermission): Boolean {
        if (managerService.isManagerPermission(
                userId = userId,
                projectId = projectId,
                authPermission = permission,
                resourceType = AuthResourceType.ENVIRONMENT_ENV_NODE
            )) {
            return true
        }
        return client.get(ServicePermissionAuthResource::class).validateUserResourcePermissionByRelation(
            userId = userId,
            projectCode = projectId,
            resourceCode = projectId,
            resourceType = AuthResourceType.ENVIRONMENT_ENV_NODE.value,
            relationResourceType = null,
            action = buildNodeAction(permission)
        ).data ?: false
    }

    // 解密后
    override fun listEnvByPermission(userId: String, projectId: String, permission: AuthPermission): Set<Long> {
        val resourceInstances = client.get(ServicePermissionAuthResource::class).getUserResourceByPermission(
            userId = userId,
            action = buildEnvAction(permission),
            projectCode = projectId,
            resourceType = AuthResourceType.ENVIRONMENT_ENVIRONMENT.value
        ).data ?: emptyList()

        return getAllEnvInstance(resourceInstances, projectId, userId).map { HashUtil.decodeIdToLong(it) }.toSet()
    }

    // 加密
    override fun listEnvByPermissions(
        userId: String,
        projectId: String,
        permissions: Set<AuthPermission>
    ): Map<AuthPermission, List<String>> {
        val actions = mutableListOf<String>()
        permissions.forEach {
            actions.add(buildEnvAction(it))
        }
        val instanceResourcesMap = client.get(ServicePermissionAuthResource::class).getUserResourcesByPermissions(
            userId = userId,
            projectCode = projectId,
            resourceType = AuthResourceType.ENVIRONMENT_ENVIRONMENT.value,
            action = actions
        ).data ?: emptyMap()
        val instanceMap = mutableMapOf<AuthPermission, List<String>>()
        instanceResourcesMap.forEach { (key, value) ->
            val envs = getAllEnvInstance(value, projectId, userId).toList()

            instanceMap[key] = envs.map { it }
        }
        logger.info("listEnvByPermissions v3Impl [$userId] [$projectId] [$instanceMap]")
        return instanceMap
    }

    // 解密后
    override fun listNodeByPermission(userId: String, projectId: String, permission: AuthPermission): Set<Long> {
        val resourceInstances = client.get(ServicePermissionAuthResource::class).getUserResourceByPermission(
            userId = userId,
            action = buildNodeAction(permission),
            projectCode = projectId,
            resourceType = AuthResourceType.ENVIRONMENT_ENV_NODE.value
        ).data ?: emptyList()

        return getAllNodeInstance(resourceInstances, projectId, userId).map { HashUtil.decodeIdToLong(it) }.toSet()
    }

    // 加密
    override fun listNodeByPermissions(
        userId: String,
        projectId: String,
        permissions: Set<AuthPermission>
    ): Map<AuthPermission, List<String>> {
        val actions = mutableListOf<String>()
        permissions.forEach {
            actions.add(buildEnvAction(it))
        }
        val instanceResourcesMap = client.get(ServicePermissionAuthResource::class).getUserResourcesByPermissions(
            userId = userId,
            projectCode = projectId,
            resourceType = AuthResourceType.ENVIRONMENT_ENV_NODE.value,
            action = actions
        ).data ?: emptyMap()
        val instanceMap = mutableMapOf<AuthPermission, List<String>>()
        instanceResourcesMap.forEach { (key, value) ->
            val nodes = getAllNodeInstance(value, projectId, userId).toList().map { it }
            logger.info("listNodeByPermissions v3Impl [$nodes] ")
            instanceMap[key] = nodes
        }
        logger.info("listNodeByPermissions v3Impl [$userId] [$projectId] [$instanceMap]")
        return instanceMap
    }

    override fun createEnv(userId: String, projectId: String, envId: Long, envName: String) {
        TODO("Not yet implemented")
    }

    override fun updateEnv(userId: String, projectId: String, envId: Long, envName: String) {
        return
    }

    override fun deleteEnv(projectId: String, envId: Long) {
        return
    }

    override fun createNode(userId: String, projectId: String, nodeId: Long, nodeName: String) {
        TODO("Not yet implemented")
    }

    override fun deleteNode(projectId: String, nodeId: Long) {
        TODO("Not yet implemented")
    }

    override fun updateNode(userId: String, projectId: String, nodeId: Long, nodeName: String) {
        return
    }

    // 拿到的数据统一为加密后的id
    private fun getAllNodeInstance(resourceCodeList: List<String>, projectId: String, userId: String): Set<String> {
        val instanceIds = mutableSetOf<String>()
        if (resourceCodeList.contains("*")) {
            val repositoryInfos = nodeDao.listNodes(dslContext, projectId)
            repositoryInfos.map {
                instanceIds.add(HashUtil.encodeLongId(it.nodeId))
            }
            return instanceIds
        }
        resourceCodeList.map { instanceIds.add(it) }
        return instanceIds
    }

    // 拿到的数据统一为加密后的id
    private fun getAllEnvInstance(resourceCodeList: List<String>, projectId: String, userId: String): Set<String> {
        val instanceIds = mutableSetOf<String>()

        if (resourceCodeList.contains("*")) {
            val repositoryInfos = envDao.list(dslContext, projectId)
            repositoryInfos.map {
                instanceIds.add(HashUtil.encodeLongId(it.envId))
            }
            return instanceIds
        }
        resourceCodeList.map { instanceIds.add(it) }
        return instanceIds
    }

    private fun buildNodeAction(authPermission: AuthPermission): String {
        return TActionUtils.buildAction(authPermission, AuthResourceType.ENVIRONMENT_ENV_NODE)
    }

    private fun buildEnvAction(authPermission: AuthPermission): String {
        return TActionUtils.buildAction(authPermission, AuthResourceType.ENVIRONMENT_ENVIRONMENT)
    }

    companion object {
        val logger = LoggerFactory.getLogger(this::class.java)
    }
}
