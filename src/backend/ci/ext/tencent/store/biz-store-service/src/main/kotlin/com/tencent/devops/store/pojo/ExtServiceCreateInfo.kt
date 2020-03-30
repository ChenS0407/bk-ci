package com.tencent.devops.store.pojo

import io.swagger.annotations.ApiModelProperty

data class ExtServiceCreateInfo(
    @ApiModelProperty("扩展服务code")
    val serviceCode: String,
    @ApiModelProperty("扩展服务Name")
    val serviceName: String,
    @ApiModelProperty("所属分类")
    val classify: String? = "",
    @ApiModelProperty("服务版本")
    val version: String,
    @ApiModelProperty("状态")
    val status: Int,
    @ApiModelProperty("状态对应的描述")
    val statusMsg: String? = null,
    @ApiModelProperty("LOGO url")
    val logoUrl: String? = null,
    @ApiModelProperty("ICON")
    val icon: String? = null,
    @ApiModelProperty("扩展服务简介")
    val summary: String? = null,
    @ApiModelProperty("扩展服务描述")
    val description: String? = null,
    @ApiModelProperty("扩展服务发布者")
    val publisher: String? = null,
    @ApiModelProperty("发布时间")
    val publishTime: Long,
    @ApiModelProperty("是否是最后版本")
    val latestFlag: Boolean? = false,
    @ApiModelProperty("删除标签")
    val deleteFlag: Boolean? = false,
    @ApiModelProperty("添加用户")
    val creatorUser: String
)