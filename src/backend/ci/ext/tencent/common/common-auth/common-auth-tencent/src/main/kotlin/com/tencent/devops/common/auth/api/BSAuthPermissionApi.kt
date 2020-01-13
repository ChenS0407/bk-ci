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

package com.tencent.devops.common.auth.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.tencent.devops.common.api.exception.RemoteServiceException
import com.tencent.devops.common.api.util.OkhttpUtils
import com.tencent.devops.common.auth.api.pojo.BkAuthPermissionGrantRequest
import com.tencent.devops.common.auth.api.pojo.BkAuthPermissionVerifyRequest
import com.tencent.devops.common.auth.api.pojo.BkAuthPermissionsPolicyCodeAndResourceType
import com.tencent.devops.common.auth.api.pojo.BkAuthPermissionsResources
import com.tencent.devops.common.auth.api.pojo.BkAuthPermissionsResourcesRequest
import com.tencent.devops.common.auth.api.pojo.BkAuthResponse
import com.tencent.devops.common.auth.code.AuthServiceCode
import com.tencent.devops.common.auth.jmx.JmxAuthApi
import com.tencent.devops.common.auth.jmx.JmxAuthApi.Companion.LIST_USER_RESOURCE
import com.tencent.devops.common.auth.jmx.JmxAuthApi.Companion.LIST_USER_RESOURCES
import com.tencent.devops.common.auth.jmx.JmxAuthApi.Companion.VALIDATE_USER_RESOURCE
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class BSAuthPermissionApi @Autowired constructor(
    private val bkAuthProperties: BkAuthProperties,
    private val objectMapper: ObjectMapper,
    private val bsAuthTokenApi: BSAuthTokenApi,
    private val jmxAuthApi: JmxAuthApi
) : AuthPermissionApi {
//    override fun addResourcePermissionForUsers(userId: String, projectCode: String, serviceCode: AuthServiceCode, permission: AuthPermission, resourceType: AuthResourceType, resourceCode: String, userIdList: List<String>, supplier: (() -> List<String>)?): Boolean {
//        logger.info("addResourcePermissionForUsers:Input($userId,$projectCode,$serviceCode,${permission.name},${resourceType.name},$resourceCode,$userIdList)")
//        val epoch = System.currentTimeMillis()
//        var success = false
//        try {
//            val accessToken = bsAuthTokenApi.getAccessToken(serviceCode)
//            val url = "${bkAuthProperties.url}/permission/project/service/policy/resource/users/grant?" +
//                "access_token=$accessToken"
//
//            val bkAuthPermissionGrantRequest = BkAuthPermissionGrantRequest(
//                projectCode = projectCode,
//                serviceCode = serviceCode.id(),
//                policyCode = permission.value,
//                resourceType = resourceType.value,
//                resourceCode = resourceCode,
//                userIdList = userIdList
//            )
//            val content = objectMapper.writeValueAsString(bkAuthPermissionGrantRequest)
//            val mediaType = MediaType.parse("application/json; charset=utf-8")
//            val requestBody = RequestBody.create(mediaType, content)
//
//            val request = Request.Builder()
//                .url(url)
//                .post(requestBody)
//                .build()
//
//            OkhttpUtils.doHttp(request).use { response ->
//                val responseContent = response.body()!!.string()
//                if (!response.isSuccessful) {
//                    logger.error("Fail to grant user permission. $responseContent")
//                    throw RemoteServiceException("Fail to grant user permission")
//                }
//
//                success = true
//                val responseObject = objectMapper.readValue<BkAuthResponse<String>>(responseContent)
//                if (responseObject.code != 0 && responseObject.code != 400) {
//                    if (responseObject.code == 403) {
//                        bsAuthTokenApi.refreshAccessToken(serviceCode)
//                    }
//                    logger.error("Fail to grant user permission. $responseContent")
//                    throw RemoteServiceException("Fail to grant user permission")
//                }
//                val result = responseObject.code == 0
//                logger.info("addResourcePermissionForUsers:Input($userId,$projectCode,$serviceCode,${permission.name},${resourceType.name},$resourceCode,$userIdList)->Output($result)")
//                return result
//            }
//        } finally {
//            jmxAuthApi.execute(LIST_USER_RESOURCE, System.currentTimeMillis() - epoch, success)
//        }
//    }

    override fun validateUserResourcePermission(
        user: String,
        serviceCode: AuthServiceCode,
        resourceType: AuthResourceType,
        projectCode: String,
        permission: AuthPermission
    ): Boolean {
        return validateUserResourcePermission(user, serviceCode, resourceType, projectCode, "*", permission)
    }

    override fun validateUserResourcePermission(
        user: String,
        serviceCode: AuthServiceCode,
        resourceType: AuthResourceType,
        projectCode: String,
        resourceCode: String,
        permission: AuthPermission
    ): Boolean {
        val epoch = System.currentTimeMillis()
        var success = false
        try {
            val accessToken = bsAuthTokenApi.getAccessToken(serviceCode)
            val url =
                "${bkAuthProperties.url}/permission/project/service/policy/resource/user/verfiy?access_token=$accessToken"
            logger.info("[$user|$serviceCode|$resourceType|$projectCode|$resourceCode|$permission] BSAuthPermissionApi url:$url")
            val bkAuthPermissionRequest = BkAuthPermissionVerifyRequest(
                projectCode,
                serviceCode.id(),
                resourceCode,
                permission.value,
                resourceType.value,
                user
            )
            val content = objectMapper.writeValueAsString(bkAuthPermissionRequest)
            val mediaType = MediaType.parse("application/json; charset=utf-8")
            val requestBody = RequestBody.create(mediaType, content)

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            OkhttpUtils.doHttp(request).use { response ->
                val responseContent = response.body()!!.string()
                if (!response.isSuccessful) {
                    logger.error("Fail to validate user permission. $responseContent")
                    throw RemoteServiceException("Fail to validate user permission")
                }

                success = true
                val responseObject = objectMapper.readValue<BkAuthResponse<String>>(responseContent)
                if (responseObject.code != 0 && responseObject.code != 400) {
                    if (responseObject.code == 403) {
                        bsAuthTokenApi.refreshAccessToken(serviceCode)
                    }
                    logger.error("Fail to validate user permission. $responseContent")
                    throw RemoteServiceException("Fail to validate user permission")
                }
                val result = responseObject.code == 0
                if (!result) {
                    logger.warn("Fail to validate the user resource permission with response: $responseContent")
                }
                return result
            }
        } finally {
            jmxAuthApi.execute(VALIDATE_USER_RESOURCE, System.currentTimeMillis() - epoch, success)
        }
    }

    override fun getUserResourceByPermission(
        user: String,
        serviceCode: AuthServiceCode,
        resourceType: AuthResourceType,
        projectCode: String,
        permission: AuthPermission,
        supplier: (() -> List<String>)?
    ): List<String> {
        val epoch = System.currentTimeMillis()
        var success = false
        try {
            val accessToken = bsAuthTokenApi.getAccessToken(serviceCode)
            val url = "${bkAuthProperties.url}/permission/project/service/policy/user/query/resources?" +
                "access_token=$accessToken&user_id=$user&project_code=$projectCode&service_code=${serviceCode.id()}" +
                "&resource_type=${resourceType.value}&policy_code=${permission.value}&is_exact_resource=1"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            OkhttpUtils.doHttp(request).use { response ->
                val responseContent = response.body()!!.string()
                if (!response.isSuccessful) {
                    logger.error("Fail to get user resource by permission. $responseContent")
                    throw RemoteServiceException("Fail to get user resource by permission")
                }

                success = true
                val responseObject = objectMapper.readValue<BkAuthResponse<List<String>>>(responseContent)
                if (responseObject.code != 0) {
                    if (responseObject.code == 403) {
                        bsAuthTokenApi.refreshAccessToken(serviceCode)
                    }
                    logger.error("Fail to get user resource by permission. $responseContent")
                    throw RemoteServiceException("Fail to get user resource by permission")
                }
                return responseObject.data ?: emptyList()
            }
        } finally {
            jmxAuthApi.execute(LIST_USER_RESOURCE, System.currentTimeMillis() - epoch, success)
        }
    }

    override fun getUserResourcesByPermissions(
        user: String,
        serviceCode: AuthServiceCode,
        resourceType: AuthResourceType,
        projectCode: String,
        permissions: Set<AuthPermission>,
        supplier: (() -> List<String>)?
    ): Map<AuthPermission, List<String>> {

        val epoch = System.currentTimeMillis()
        var success = false
        try {
            val accessToken = bsAuthTokenApi.getAccessToken(serviceCode)
            val url =
                "${bkAuthProperties.url}/permission/project/service/policies/user/query/resources?access_token=$accessToken"
            val policyResourceTypeList = permissions.map {
                BkAuthPermissionsPolicyCodeAndResourceType(it.value, resourceType.value)
            }
            val bkAuthPermissionsResourcesRequest = BkAuthPermissionsResourcesRequest(
                projectCode,
                serviceCode.id(),
                policyResourceTypeList,
                user
            )
            val content = objectMapper.writeValueAsString(bkAuthPermissionsResourcesRequest)
            val mediaType = MediaType.parse("application/json; charset=utf-8")
            val requestBody = RequestBody.create(mediaType, content)

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            OkhttpUtils.doHttp(request).use { response ->
                val responseContent = response.body()!!.string()
                if (!response.isSuccessful) {
                    logger.error("Fail to get user resources by permissions. $responseContent")
                    throw RemoteServiceException("Fail to get user resources by permissions")
                }

                success = true
                val responseObject =
                    objectMapper.readValue<BkAuthResponse<List<BkAuthPermissionsResources>>>(responseContent)
                if (responseObject.code != 0) {
                    if (responseObject.code == 403) {
                        bsAuthTokenApi.refreshAccessToken(serviceCode)
                    }
                    logger.error("Fail to get user resources by permissions. $responseContent")
                    throw RemoteServiceException("Fail to get user resources by permissions")
                }

                val permissionsResourcesMap = mutableMapOf<AuthPermission, List<String>>()
                responseObject.data!!.forEach {
                    val bkAuthPermission = AuthPermission.get(it.policyCode)
                    val resourceList = it.resourceCodeList
                    permissionsResourcesMap[bkAuthPermission] = resourceList
                }
                return permissionsResourcesMap
            }
        } finally {
            jmxAuthApi.execute(LIST_USER_RESOURCES, System.currentTimeMillis() - epoch, success)
        }
    }

    override fun getUserResourcesByPermissions(
        userId: String,
        scopeType: String,
        scopeId: String,
        resourceType: AuthResourceType,
        permissions: Set<AuthPermission>,
        systemId: AuthServiceCode,
        supplier: (() -> List<String>)?
    ): Map<AuthPermission, List<String>> {
        val epoch = System.currentTimeMillis()
        var success = false
        try {
            val accessToken = bsAuthTokenApi.getAccessToken(systemId)
            val url =
                "${bkAuthProperties.url}/permission/project/service/policies/user/query/resources?access_token=$accessToken"
            val policyResourceTypeList = permissions.map {
                BkAuthPermissionsPolicyCodeAndResourceType(it.value, resourceType.value)
            }
            val bkAuthPermissionsResourcesRequest = BkAuthPermissionsResourcesRequest(
                projectCode = scopeId,
                serviceCode = systemId.id(),
                policyResourceTypeList = policyResourceTypeList,
                userId = userId
            )
            val content = objectMapper.writeValueAsString(bkAuthPermissionsResourcesRequest)
            val mediaType = MediaType.parse("application/json; charset=utf-8")
            val requestBody = RequestBody.create(mediaType, content)

            val request = Request.Builder().url(url).post(requestBody).build()

            OkhttpUtils.doHttp(request).use { response ->
                val responseContent = response.body()!!.string()
                if (!response.isSuccessful) {
                    logger.error("Fail to get user resources by permissions. $responseContent")
                    throw RemoteServiceException("Fail to get user resources by permissions")
                }

                success = true
                val responseObject =
                    objectMapper.readValue<BkAuthResponse<List<BkAuthPermissionsResources>>>(responseContent)
                if (responseObject.code != 0) {
                    if (responseObject.code == 403) {
                        bsAuthTokenApi.refreshAccessToken(systemId)
                    }
                    logger.error("Fail to get user resources by permissions. $responseContent")
                    throw RemoteServiceException("Fail to get user resources by permissions")
                }

                val permissionsResourcesMap = mutableMapOf<AuthPermission, List<String>>()
                responseObject.data!!.forEach {
                    val bkAuthPermission = AuthPermission.get(it.policyCode)
                    val resourceList = it.resourceCodeList
                    permissionsResourcesMap[bkAuthPermission] = resourceList
                }
                return permissionsResourcesMap
            }
        } finally {
            jmxAuthApi.execute(LIST_USER_RESOURCES, System.currentTimeMillis() - epoch, success)
        }
    }

    override fun addResourcePermissionForUsers(
        userId: String,
        projectCode: String,
        serviceCode: AuthServiceCode,
        permission: AuthPermission,
        resourceType: AuthResourceType,
        resourceCode: String,
        userIdList: List<String>,
        supplier: (() -> List<String>)?
    ): Boolean {
        var result = false
        val accessToken = bsAuthTokenApi.getAccessToken(serviceCode)
        val url =
            "${bkAuthProperties.url}/permission/project/service/policy/resource/users/grant?access_token=$accessToken"

        val grantRequest = BkAuthPermissionGrantRequest(
            projectCode = projectCode,
            serviceCode = serviceCode.id(),
            policyCode = permission.value,
            resourceCode = resourceCode,
            resourceType = resourceType.value,
            userIdList = userIdList
        )
        val mediaType = MediaType.parse("application/json; charset=utf-8")
        val content = objectMapper.writeValueAsString(grantRequest)
        logger.info("addResourcePermissionForUsers url[$url], body[$content]")
        val requestBody = RequestBody.create(mediaType, content)
        val request = Request.Builder().url(url).post(requestBody).build()
        OkhttpUtils.doHttp(request).use { response ->
            val responseContent = response.body()!!.string()
            if (!response.isSuccessful) {
                logger.error("createUserPermissions fail : user[$userId], projectCode[$projectCode]")
                throw RemoteServiceException("add Resource Permission remote fail")
            }
            val responseObject =
                objectMapper.readValue<BkAuthResponse<String>>(responseContent)
            logger.info("addResourcePermissionForUsers responseObject[$responseObject]")
            if (responseObject.code != 0) {
                logger.error("createUserPermissions fail : user[$userId], projectCode[$projectCode], message:$responseObject")
                throw RemoteServiceException("add Resource Permission remote fail,message:$responseObject")
            }
            result = true
        }

        return result
    }

    companion object {
        private val logger = LoggerFactory.getLogger(BSAuthPermissionApi::class.java)
    }
}
