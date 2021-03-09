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

package com.tencent.devops.store.api.container

import com.tencent.devops.store.pojo.container.BuildResource
import com.tencent.devops.store.pojo.container.BuildResourceRequest
import com.tencent.devops.common.api.pojo.Result
import io.swagger.annotations.Api
import io.swagger.annotations.ApiOperation
import io.swagger.annotations.ApiParam
import javax.ws.rs.Consumes
import javax.ws.rs.DELETE
import javax.ws.rs.GET
import javax.ws.rs.POST
import javax.ws.rs.PUT
import javax.ws.rs.Path
import javax.ws.rs.PathParam
import javax.ws.rs.Produces
import javax.ws.rs.core.MediaType

@Api(tags = ["OP_PIPELINE_BUILD_RESOURCE"], description = "OP-流水线-构建资源")
@Path("/op/pipeline/build/resource")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface OpBuildResourceResource {

    @ApiOperation("添加流水线构建资源信息")
    @POST
    @Path("/")
    fun add(
        @ApiParam(value = "流水线构建资源请求体", required = true)
        buildResourceRequest: BuildResourceRequest
    ): Result<Boolean>

    @ApiOperation("更新流水线构建资源信息")
    @PUT
    @Path("/{id}")
    fun update(
        @ApiParam("流水线构建资源ID", required = true)
        @PathParam("id")
        id: String,
        @ApiParam(value = "流水线构建资源请求体", required = true)
        buildResourceRequest: BuildResourceRequest
    ): Result<Boolean>

    @ApiOperation("获取所有流水线构建资源信息")
    @GET
    @Path("/")
    fun listAllPipelineBuildResources(): Result<List<BuildResource>>

    @ApiOperation("根据ID获取流水线构建资源信息")
    @GET
    @Path("/{id}")
    fun getPipelineBuildResourceById(
        @ApiParam("流水线构建资源ID", required = true)
        @PathParam("id")
        id: String
    ): Result<BuildResource?>

    @ApiOperation("根据ID获取流水线构建资源信息")
    @DELETE
    @Path("/{id}")
    fun deletePipelineBuildResourceById(
        @ApiParam("流水线构建资源ID", required = true)
        @PathParam("id")
        id: String
    ): Result<Boolean>
}
