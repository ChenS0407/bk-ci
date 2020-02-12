package com.tencent.devops.store.pojo.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.tencent.devops.repository.pojo.enums.VisibilityLevelEnum
import com.tencent.devops.store.pojo.ExtsionInfoReq
import io.swagger.annotations.ApiModelProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class InitExtServiceDTO(
    @ApiModelProperty("扩展服务code")
    val serviceCode: String,
    @ApiModelProperty("扩展服务Name")
    val serviceName: String,
    @ApiModelProperty("调试项目Id")
    val itemId: String,
    @ApiModelProperty("调试项目Name")
    val itemCode: String,
    @ApiModelProperty("服务语言")
    val language: String,
    @ApiModelProperty("认证方式", required = false)
    val authType: String? = null,
    @ApiModelProperty(value = "插件代码库不开源原因", required = false)
    val privateReason: String? = null,
    @ApiModelProperty("扩展点列表")
    val extensionList: List<ExtsionInfoReq>,
    @ApiModelProperty(value = "项目可视范围", required = false)
    val visibilityLevel: VisibilityLevelEnum? = VisibilityLevelEnum.PRIVATE,
    @ApiModelProperty("添加用户")
    val creatorUser: String

)