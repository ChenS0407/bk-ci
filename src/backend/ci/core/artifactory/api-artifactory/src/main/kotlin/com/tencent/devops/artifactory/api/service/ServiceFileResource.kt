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

package com.tencent.devops.artifactory.api.service

import com.tencent.devops.artifactory.pojo.enums.FileChannelTypeEnum
import com.tencent.devops.common.api.auth.AUTH_HEADER_USER_ID
import com.tencent.devops.common.api.pojo.Result
import io.swagger.annotations.Api
import io.swagger.annotations.ApiOperation
import io.swagger.annotations.ApiParam
import org.glassfish.jersey.media.multipart.FormDataContentDisposition
import org.glassfish.jersey.media.multipart.FormDataParam
import java.io.InputStream
import javax.servlet.http.HttpServletResponse
import javax.ws.rs.Consumes
import javax.ws.rs.GET
import javax.ws.rs.HeaderParam
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.QueryParam
import javax.ws.rs.core.Context
import javax.ws.rs.core.MediaType

/**
 * 注意，此类用了MULTIPART_FORM_DATA，导致Feign会有问题，不要直接用Feign去调用。
 * 需要扩展请自行创建新的Service接口
 */
@Api(tags = ["SERVICE_ARTIFACTORY_FILE"], description = "仓库-文件管理")
@Path("/service/artifactories/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface ServiceFileResource {

    @ApiOperation("上传文件")
    @POST
    @Path("/file/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    fun uploadFile(
        @ApiParam("userId", required = true)
        @QueryParam("userId")
        userId: String,
        @ApiParam("文件", required = true)
        @FormDataParam("file")
        inputStream: InputStream,
        @FormDataParam("file")
        disposition: FormDataContentDisposition,
        @ApiParam("项目代码", required = false)
        @QueryParam("projectCode")
        projectCode: String? = null,
        @ApiParam("渠道类型", required = true)
        @QueryParam("fileChannelType")
        fileChannelType: FileChannelTypeEnum = FileChannelTypeEnum.SERVICE
    ): Result<String?>

    @ApiOperation("下载文件")
    @GET
    @Path("/file/download")
    fun downloadFile(
        @ApiParam("用户ID", required = true)
        @HeaderParam(AUTH_HEADER_USER_ID)
        userId: String,
        @ApiParam("文件路径", required = true)
        @QueryParam("filePath")
        filePath: String,
        @Context
        response: HttpServletResponse
    )
}
