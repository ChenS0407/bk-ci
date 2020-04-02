package com.tencent.devops.store.api

import com.tencent.devops.common.api.auth.AUTH_HEADER_DEVOPS_ACCESS_TOKEN
import com.tencent.devops.common.api.auth.AUTH_HEADER_USER_ID
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.store.pojo.common.InstalledProjRespItem
import com.tencent.devops.store.pojo.common.UnInstallReq
import com.tencent.devops.store.pojo.dto.InstallExtServiceReq
import com.tencent.devops.store.pojo.vo.ExtServiceRespItem
import io.swagger.annotations.Api
import io.swagger.annotations.ApiOperation
import io.swagger.annotations.ApiParam
import javax.ws.rs.Consumes
import javax.ws.rs.GET
import javax.ws.rs.HeaderParam
import javax.ws.rs.POST
import javax.ws.rs.PUT
import javax.ws.rs.Path
import javax.ws.rs.PathParam
import javax.ws.rs.Produces
import javax.ws.rs.QueryParam
import javax.ws.rs.core.MediaType

@Api(tags = ["USER_EXT_SERVICE_PROJECT"], description = "研发商店-扩展服务项目间关系")
@Path("/user/market/service/project")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface UserExtServiceProjectResource {
    @ApiOperation("安装扩展服务到项目")
    @POST
    @Path("/install")
    fun installImage(
        @ApiParam("token", required = true)
        @HeaderParam(AUTH_HEADER_DEVOPS_ACCESS_TOKEN)
        accessToken: String,
        @ApiParam("userId", required = true)
        @HeaderParam(AUTH_HEADER_USER_ID)
        userId: String,
        @ApiParam("安装扩展服务到项目请求报文体", required = true)
        installExtServiceReq: InstallExtServiceReq
    ): Result<Boolean>

    @ApiOperation("根据扩展服务标识获取已安装的项目列表")
    @GET
    @Path("/installedProjects/{serviceCode}")
    fun getInstalledProjects(
        @ApiParam("token", required = true)
        @HeaderParam(AUTH_HEADER_DEVOPS_ACCESS_TOKEN)
        accessToken: String,
        @ApiParam("userId", required = true)
        @HeaderParam(AUTH_HEADER_USER_ID)
        userId: String,
        @ApiParam("模版代码", required = true)
        @PathParam("serviceCode")
        serviceCode: String
    ): Result<List<InstalledProjRespItem>>

    @ApiOperation("根据项目查询扩展服务")
    @GET
    @Path("/{projectCode}/installed/service")
    fun getServiceByInstalledProject(
        @ApiParam("token", required = true)
        @HeaderParam(AUTH_HEADER_DEVOPS_ACCESS_TOKEN)
        accessToken: String,
        @ApiParam("userId", required = true)
        @HeaderParam(AUTH_HEADER_USER_ID)
        userId: String,
        @ApiParam("模版代码", required = true)
        @PathParam("projectCode")
        projectCode: String
    ): Result<List<ExtServiceRespItem>>

    @ApiOperation("卸载扩展服务")
    @PUT
    @Path("{projectCode}/serviceCodes/{serviceCode}/uninstalled/")
    fun unInstallService(
        @ApiParam("token", required = true)
        @HeaderParam(AUTH_HEADER_DEVOPS_ACCESS_TOKEN)
        accessToken: String,
        @ApiParam("userId", required = true)
        @HeaderParam(AUTH_HEADER_USER_ID)
        userId: String,
        @ApiParam("服务Code", required = true)
        @PathParam("serviceCode")
        serviceCode: String,
        @ApiParam("服务Code", required = true)
        @PathParam("projectCode")
        projectCode: String,
        @ApiParam("卸载扩展请求包体", required = true)
        unInstallReq: UnInstallReq
    ): Result<Boolean>
}