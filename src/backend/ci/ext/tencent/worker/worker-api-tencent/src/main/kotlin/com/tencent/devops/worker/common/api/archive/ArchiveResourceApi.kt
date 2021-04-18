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

package com.tencent.devops.worker.common.api.archive

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.gson.JsonParser
import com.tencent.devops.artifactory.pojo.enums.FileTypeEnum
import com.tencent.devops.common.api.exception.TaskExecuteException
import com.tencent.devops.common.api.pojo.ErrorCode
import com.tencent.devops.common.api.pojo.ErrorType
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.process.pojo.BuildVariables
import com.tencent.devops.worker.common.api.AbstractBuildResourceApi
import com.tencent.devops.worker.common.api.ApiPriority
import com.tencent.devops.worker.common.logger.LoggerService
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import java.io.File
import java.net.URLEncoder

@ApiPriority(priority = 9)
class ArchiveResourceApi : AbstractBuildResourceApi(), ArchiveSDKApi {
    private val bkrepoResourceApi = BkRepoResourceApi()

    private fun getParentFolder(path: String): String {
        val tmpPath = path.removeSuffix("/")
        return tmpPath.removeSuffix(getFileName(tmpPath))
    }

    private fun getFileName(path: String): String {
        return path.removeSuffix("/").split("/").last()
    }

    override fun getFileDownloadUrls(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String,
        fileType: FileTypeEnum,
        customFilePath: String?
    ): List<String> {
        val repoName: String
        val filePath: String
        val fileName: String
        if (fileType == FileTypeEnum.BK_CUSTOM) {
            repoName = "custom"
            val normalizedPath = "/${customFilePath!!.removePrefix("./").removePrefix("/")}"
            filePath = getParentFolder(normalizedPath)
            fileName = getFileName(normalizedPath)
        } else {
            repoName = "pipeline"
            filePath = "/$pipelineId/$buildId/"
            fileName = getFileName(customFilePath!!)
        }

        return bkrepoResourceApi.queryByPathEqOrNameMatchOrMetadataEqAnd(
            userId = userId,
            projectId = projectId,
            repoNames = listOf(repoName),
            filePaths = listOf(filePath),
            fileNames = listOf(fileName),
            metadata = mapOf(),
            page = 0,
            pageSize = 10000
        ).map { it.fullPath }
    }


    private fun uploadBkRepoCustomize(file: File, destPath: String, buildVariables: BuildVariables) {
        val bkRepoPath = destPath.removeSuffix("/") + "/" + file.name
        val url = "/bkrepo/api/build/generic/${buildVariables.projectId}/custom/$bkRepoPath"
        val request = buildPut(
            url,
            RequestBody.create(MediaType.parse("application/octet-stream"), file),
            bkrepoResourceApi.getUploadHeader(file, buildVariables, true),
            useFileGateway = true
        )
        val response = request(request, "上传自定义文件失败")
        try {
            val obj = JsonParser().parse(response).asJsonObject
            if (obj.has("code") && obj["code"].asString != "0") throw RuntimeException()
        } catch (e: Exception) {
            logger.error(e.message ?: "")
            throw TaskExecuteException(
                errorCode = ErrorCode.USER_TASK_OPERATE_FAIL,
                errorType = ErrorType.USER,
                errorMsg = "archive fail: $response"
            )
        }
    }

    override fun uploadCustomize(file: File, destPath: String, buildVariables: BuildVariables) {
        if (bkrepoResourceApi.useBkRepo()) {
            val destFullPath = destPath.removePrefix("/").removePrefix("./").removeSuffix("/") + "/" + file.name
            val token = bkrepoResourceApi.createBkRepoUploadToken("custom", buildVariables)
            bkrepoResourceApi.uploadBkRepoByToken(
                file,
                buildVariables.projectId,
                "custom",
                destFullPath,
                token,
                buildVariables,
                parseAppData = true
            )
        } else {
            uploadBkRepoCustomize(file, destPath, buildVariables)
        }
    }

    override fun uploadPipeline(file: File, buildVariables: BuildVariables) {
        if (bkrepoResourceApi.useBkRepo()) {
            val destFullPath = "${buildVariables.pipelineId}/${buildVariables.buildId}/${file.name}"
            val token = bkrepoResourceApi.createBkRepoUploadToken("pipeline", buildVariables)
            bkrepoResourceApi.uploadBkRepoByToken(
                file,
                buildVariables.projectId,
                "pipeline",
                destFullPath,
                token,
                buildVariables,
                parseAppData = true
            )
        } else {
            uploadBkRepoPipeline(file, buildVariables)
        }
        bkrepoResourceApi.setPipelineMetadata("pipeline", buildVariables)
    }


    private fun uploadBkRepoPipeline(file: File, buildVariables: BuildVariables) {
        logger.info("upload file >>> ${file.name}")
        val url = "/bkrepo/api/build/generic/${buildVariables.projectId}/pipeline/${buildVariables.pipelineId}/${buildVariables.buildId}/${file.name}"
        val request = buildPut(
            url,
            RequestBody.create(MediaType.parse("application/octet-stream"), file),
            bkrepoResourceApi.getUploadHeader(file, buildVariables, true),
            useFileGateway = true
        )
        val response = request(request, "上传流水线文件失败")
        try {
            val obj = JsonParser().parse(response).asJsonObject
            if (obj.has("code") && obj["code"].asString != "0") throw RuntimeException()
        } catch (e: Exception) {
            logger.error(e.message ?: "")
        }
    }

    private fun downloadBkRepoFile(user: String, projectId: String, repoName: String, fullpath: String, destPath: File) {
        val url = "/bkrepo/api/build/generic/$projectId/$repoName$fullpath"
        var header = HashMap<String, String>()
        header.set("X-BKREPO-UID", user)
        val request = buildGet(url, header, true)
        download(request, destPath)
    }

    override fun downloadCustomizeFile(
        userId: String,
        projectId: String,
        uri: String,
        destPath: File
    ) {
        downloadBkRepoFile(userId, projectId, "custom", uri, destPath)
    }

    override fun downloadPipelineFile(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String,
        uri: String,
        destPath: File
    ) {
        downloadBkRepoFile(userId, projectId, "pipeline", uri, destPath)
    }

    /*
     * 此处绑定了jfrog的plugin实现接口，用于给用户颁发临时密钥用于docker push
     */
    override fun dockerBuildCredential(projectId: String): Map<String, String> {
        val path = "/dockerbuild/credential"
        val request = buildGet(path)
        val responseContent = request(request, "获取凭证信息失败")
        return jacksonObjectMapper().readValue(responseContent)
    }


    private fun tryEncode(str: String?): String {
        return if (str.isNullOrBlank()) {
            ""
        } else {
            URLEncoder.encode(str, "UTF-8")
        }
    }

    override fun uploadFile(
        url: String,
        destPath: String,
        file: File,
        headers: Map<String, String>?
    ): Result<Boolean> {
        LoggerService.addNormalLine("upload file url >>> $url")
        val fileBody = RequestBody.create(MultipartFormData, file)
        val fileName = file.name
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, fileBody)
            .build()
        val request = buildPost(url, requestBody, headers ?: emptyMap(), useFileGateway = true)
        val responseContent = request(request, "upload file[$fileName] failed")
        return objectMapper.readValue(responseContent)
    }

    companion object {

    }
}
